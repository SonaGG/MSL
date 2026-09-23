#include <metal_stdlib>
using namespace metal;

struct Bindless {
    array<texture2d<float>, 16384> textures [[id(0)]];
    array<sampler, 512> samplers [[id(16384)]];
};

struct Material {
    texture2d<float> albedo [[id(0)]];
    texture2d<float> normal [[id(1)]];
    sampler linear [[id(2)]];
    device const float4* tints [[id(3)]];
};

struct Varyings {
    float4 position [[position]];
    float2 uv;
    uint texture [[flat]];
    uint sampler [[flat]];
};

fragment float4 bindlessFragment(Varyings in [[stage_in]],
                                 constant Bindless& bindless [[buffer(0)]],
                                 constant Material& material [[buffer(1)]],
                                 constant uint& tintIndex [[buffer(2)]])
{
    float4 color = bindless.textures[in.texture].sample(bindless.samplers[in.sampler], in.uv);
    color *= material.albedo.sample(material.linear, in.uv);
    color.rgb += material.normal.sample(material.linear, in.uv).xyz * 0.5;
    return color * material.tints[tintIndex];
}
