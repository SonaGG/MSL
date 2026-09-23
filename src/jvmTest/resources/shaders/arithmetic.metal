#include <metal_stdlib>
using namespace metal;

struct Settings {
    uint divisor;
    float scale;
    float bias;
    float4x4 transform;
    float4 tint;
};

struct Varyings {
    float4 position [[position]];
    float4 values;
    uint4 bits [[flat]];
};

fragment float4 arithmeticFragment(Varyings input [[stage_in]],
                                   constant Settings& settings [[buffer(0)]],
                                   device float* history [[buffer(4)]],
                                   device float* other [[buffer(5)]])
{
    uint a = input.bits.x;
    uint b = input.bits.y;
    uint quotients = a / 3u + a / 7u + a / 10u + a / 1000u + b / 0xFFFFFFF1u + b / 641u;
    uint remainders = a % 3u + a % 7u + b % 10u + b % 0x80000001u;
    uint products = a * 3u + a * 6u + a * 7u + b * 10u + b * 0x60u + b * 15u;

    float d = input.values.w + 2.0;
    float divided = input.values.x / d + input.values.y / d + input.values.z / d;

    float mask = input.values.x > 0.5 ? 1.0 : 0.0;
    float masked = mask * input.values.y + step(0.25, input.values.z) * 3.0 + (input.values.w > 0.0 ? 2.0 : 4.0) * 0.5;

    float cancel = (input.values.x + input.values.y) - input.values.x + (input.values.z - input.values.z * 1.0);
    uint bitwise = (a & b & a) | (b ^ a ^ b);

    history[0] = input.values.x;
    float first = history[0];
    history[0] = first * 2.0;
    other[1] = 3.0;
    float reread = history[0] + other[1];
    history[1] = reread;
    history[1] = reread + 1.0;

    float4 matrixConstant = settings.transform * float4(settings.scale, settings.bias, 1.0, 0.0);
    float4 result = float4(float(quotients % 1024u) * 0.001, float(remainders) * 0.01, float(products & 1023u) * 0.001, divided);
    if (input.values.w > 0.75) {
        float expensive = sqrt(abs(input.values.x * settings.scale)) + matrixConstant.x;
        result.x += expensive;
    }
    result.y += masked + cancel + float(bitwise & 15u) + settings.tint.y;
    result.z += matrixConstant.z * settings.tint.x;
    return result;
}
