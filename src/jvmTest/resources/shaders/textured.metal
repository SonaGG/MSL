#include <metal_stdlib>
using namespace metal;

struct VertexOut {
    float4 position [[position]];
    float2 uv;
    half4 tint [[flat]];
};

vertex VertexOut texturedVertex(uint vid [[vertex_id]], constant float4* positions [[buffer(0)]])
{
    VertexOut out;
    float4 p = positions[vid];
    out.position = p;
    out.uv = p.xy * 0.5 + 0.5;
    out.tint = half4(1.0h, 0.5h, 0.25h, 1.0h);
    return out;
}

constexpr sampler linearSampler(coord::normalized, filter::linear, address::repeat, mip_filter::linear);

fragment float4 texturedFragment(VertexOut in [[stage_in]],
                                 texture2d<float> albedo [[texture(0)]],
                                 texture2d<float, access::read> lookup [[texture(1)]],
                                 depth2d<float> shadow [[texture(2)]],
                                 sampler pointSampler [[sampler(0)]])
{
    float4 color = albedo.sample(linearSampler, in.uv);
    color += albedo.sample(pointSampler, in.uv, level(1.0));
    color += albedo.sample(pointSampler, in.uv, bias(0.5), int2(1, -1));
    color *= lookup.read(uint2(in.position.xy));
    float visibility = shadow.sample_compare(pointSampler, in.uv, 0.5);
    float4 gathered = albedo.gather(linearSampler, in.uv, int2(0), component::y);
    uint width = albedo.get_width();
    uint levels = albedo.get_num_mip_levels();
    color.rgb *= visibility * float(width + levels) * gathered.x;
    return color * float4(in.tint);
}
