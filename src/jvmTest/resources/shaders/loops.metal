#include <metal_stdlib>
using namespace metal;

struct Light {
    float4 position;
    float4 color;
};

struct Scene {
    uint count;
    uint mode;
    float ambient;
    float falloff;
    Light lights[8];
};

struct Varyings {
    float4 position [[position]];
    float3 world;
    float3 normal;
};

fragment float4 shadeLights(Varyings in [[stage_in]], constant Scene& scene [[buffer(0)]])
{
    float3 normal = normalize(in.normal);
    float3 total = float3(scene.ambient);
    for (uint i = 0; i < min(scene.count, 8u); ++i) {
        float3 offset = scene.lights[i].position.xyz - in.world;
        float distance2 = dot(offset, offset);
        float3 direction = offset * rsqrt(distance2);
        float lambert = saturate(dot(normal, direction));
        if (scene.mode == 1u) {
            total += scene.lights[i].color.rgb * lambert / (1.0 + scene.falloff * distance2);
        } else {
            total += scene.lights[i].color.rgb * lambert;
        }
    }
    return float4(total, 1.0);
}
