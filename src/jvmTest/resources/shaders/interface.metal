#include <metal_stdlib>
using namespace metal;

struct VertexIn {
    float3 position [[attribute(0)]];
    half4 color [[attribute(1)]];
    uint4 bones [[attribute(2)]];
    short2 offset [[attribute(3)]];
};

struct Varyings {
    float4 position [[position]];
    float clip [[clip_distance]] [2];
    half4 color;
    float2 flatValue [[flat]];
    float3 centroidValue [[centroid_perspective]];
    float2 linear [[center_no_perspective]];
    float sampled [[sample_perspective]];
    uint layer [[render_target_array_index]];
    uint viewport [[viewport_array_index]];
};

vertex Varyings interfaceVertex(VertexIn in [[stage_in]], uint vid [[vertex_id]], uint iid [[instance_id]])
{
    Varyings out;
    out.position = float4(in.position + float3(float(in.offset.x), float(in.offset.y), 0.0), 1.0);
    out.clip[0] = in.position.x;
    out.clip[1] = in.position.y;
    out.color = in.color;
    out.flatValue = float2(float(vid), float(iid));
    out.centroidValue = in.position;
    out.linear = float2(in.bones.xy);
    out.sampled = float(in.bones.z);
    out.layer = iid & 3u;
    out.viewport = 0u;
    return out;
}

struct FragmentOut {
    half4 color [[color(0)]];
    float depth [[depth(greater)]];
    uint mask [[sample_mask]];
};

fragment FragmentOut interfaceFragment(Varyings in [[stage_in]],
                                       bool front [[front_facing]],
                                       uint sample [[sample_id]],
                                       uint coverage [[sample_mask]],
                                       uint primitive [[primitive_id]])
{
    FragmentOut out;
    out.color = in.color * half(in.sampled + in.linear.x + in.centroidValue.z + in.flatValue.y);
    if (!front) {
        out.color.a = 0.0h;
    }
    out.depth = in.position.z + float(sample) * 0.001 + float(primitive & 1u);
    out.mask = coverage & (1u << sample) | in.layer;
    return out;
}
