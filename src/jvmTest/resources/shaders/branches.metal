#include <metal_stdlib>
using namespace metal;

struct Material {
    float4 base;
    float4 emissive;
    float roughness;
};

kernel void resolveBranches(device float4* output [[buffer(4)]],
                            constant Material& material [[buffer(0)]],
                            device const float* weights [[buffer(5)]],
                            uint gid [[thread_position_in_grid]])
{
    float weight = weights[gid];
    if (weight > 0.5) {
        float4 base = material.base * material.roughness;
        output[gid] = base * weight + material.emissive;
    } else {
        float4 base = material.base * material.roughness;
        output[gid] = base * (1.0 - weight);
    }
}
