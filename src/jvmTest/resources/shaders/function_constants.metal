#include <metal_stdlib>
using namespace metal;

constant bool useFog [[function_constant(0)]];
constant float fogDensity [[function_constant(1)]];
constant float weights[3] = { 0.25, 0.5, 0.25 };

struct FragmentIn {
    float4 position [[position]];
    float depth [[user(locn3)]];
    int material [[flat]];
};

struct FragmentOut {
    float4 color [[color(0)]];
    float4 normal [[color(1)]];
    float depth [[depth(any)]];
};

fragment FragmentOut shade(FragmentIn in [[stage_in]], constant float4* palette [[buffer(0)]])
{
    FragmentOut out;
    float4 color = palette[in.material];
    float w = 0.0;
    for (int i = 0; i < 3; ++i) {
        w += weights[i] * float(i);
    }
    if (useFog) {
        color.rgb = mix(color.rgb, float3(0.5), 1.0 - exp(-fogDensity * in.depth));
    }
    if (is_function_constant_defined(fogDensity) && color.a < 0.1) {
        discard_fragment();
    }
    out.color = color * w;
    out.normal = float4(0.0, 0.0, 1.0, 0.0);
    out.depth = in.position.z;
    return out;
}
