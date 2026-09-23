#include <metal_stdlib>
using namespace metal;

struct Light {
    float3 position;
    half intensity;
    ushort kind;
    bool enabled;
    char priority;
    float3x3 basis;
    packed_half3 tint;
    int2 range;
};

struct Scene {
    float4x4 viewProjection;
    Light lights[4];
    ulong seed;
    uint count;
};

struct Result {
    float4 color;
    long accumulator;
    uchar4 bytes;
    short sum;
    bool valid;
};

kernel void shadeTypes(constant Scene& scene [[buffer(0)]],
                       device Result* results [[buffer(1)]],
                       device const Light* extra [[buffer(2)]],
                       device half* halves [[buffer(3)]],
                       device ulong* wide [[buffer(4)]],
                       uint gid [[thread_position_in_grid]])
{
    float3 color = 0.0;
    long accumulator = long(scene.seed);
    short sum = 0;
    for (uint i = 0; i < min(scene.count, 4u); ++i) {
        Light light = scene.lights[i];
        if (!light.enabled) {
            continue;
        }
        color += light.basis * light.position * float(light.intensity) * float3(light.tint);
        accumulator += long(light.range.x) * long(light.range.y) - long(light.priority);
        sum += short(light.kind) + short(light.priority);
    }
    Light fallback = extra[gid];
    color += fallback.position;
    float4 projected = scene.viewProjection * float4(color, 1.0);
    ulong mixed = wide[gid] ^ (ulong(accumulator) << 7) ^ (ulong(scene.seed) >> 3);
    wide[gid] = mixed * 31ul + ulong(sum);
    halves[gid] = half(projected.w) + halves[gid + 1];
    Result result;
    result.color = projected;
    result.accumulator = accumulator + long(mixed & 0xFFFFul);
    result.bytes = uchar4(uchar(gid), uchar(sum), uchar(fallback.priority), 255);
    result.sum = sum;
    result.valid = accumulator > 0;
    results[gid] = result;
    char c = char(gid) - char(100);
    uchar u = uchar(c) >> 2;
    results[gid].bytes.x += u;
}
