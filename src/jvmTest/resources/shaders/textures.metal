#include <metal_stdlib>
using namespace metal;

struct In {
    float4 position [[position]];
    float2 uv [[user(texcoord)]];
};

struct Out {
    float4 color [[color(0)]];
    uint4 ids [[color(1)]];
};

fragment Out sampleAll(In in [[stage_in]],
                       texture1d<float> line [[texture(0)]],
                       texture2d_array<float> layers [[texture(1)]],
                       texture3d<float> volume [[texture(2)]],
                       texturecube<float> sky [[texture(3)]],
                       texturecube_array<float> probes [[texture(4)]],
                       depth2d_array<float> cascades [[texture(5)]],
                       depthcube<float> pointShadow [[texture(6)]],
                       texture2d_ms<float> msaa [[texture(7)]],
                       texture_buffer<float> table [[texture(8)]],
                       texture2d<uint> ids [[texture(9)]],
                       texture2d<int> signedIds [[texture(10)]],
                       sampler smooth [[sampler(0)]],
                       sampler compare [[sampler(1)]])
{
    Out out;
    float4 c = line.sample(smooth, in.uv.x);
    c += layers.sample(smooth, in.uv, 2u);
    c += layers.sample(smooth, in.uv, 1u, gradient2d(float2(0.01), float2(0.02)));
    c += volume.sample(smooth, float3(in.uv, 0.5), level(2.0));
    c += sky.sample(smooth, float3(in.uv, 1.0));
    c += probes.sample(smooth, float3(in.uv, -1.0), 3u, bias(1.0));
    c += float4(cascades.sample_compare(compare, in.uv, 1u, 0.5));
    c += float4(pointShadow.sample_compare(compare, float3(in.uv, 1.0), 0.25));
    c += cascades.gather_compare(compare, in.uv, 0u, 0.5);
    c += layers.gather(smooth, in.uv, 0u, int2(1, 1), component::w);
    c += msaa.read(uint2(in.position.xy), 1u);
    c += table.read(uint(in.position.x));
    c += volume.read(uint3(1, 2, 3), 1u);
    c += float4(sky.get_width(), layers.get_array_size(), volume.get_depth(1u), msaa.get_num_samples());
    c += float4(float(probes.get_array_size()), float(line.get_width()), float(table.get_width()), float(sky.get_num_mip_levels()));
    c.x += layers.calculate_clamped_lod(smooth, in.uv) + layers.calculate_unclamped_lod(smooth, in.uv);
    out.color = c;
    out.ids = ids.read(uint2(0, 0)) + uint4(signedIds.read(uint2(1, 1), 0u));
    return out;
}

kernel void writeAll(texture1d<float, access::write> line [[texture(0)]],
                     texture3d<float, access::read_write> volume [[texture(1)]],
                     texture2d_array<uint, access::write> layers [[texture(2)]],
                     texture_buffer<float, access::read_write> table [[texture(3)]],
                     texture2d<int, access::read_write> counters [[texture(4)]],
                     uint3 gid [[thread_position_in_grid]])
{
    line.write(float4(1.0), gid.x);
    float4 v = volume.read(gid);
    volume.write(v * 0.5, gid);
    layers.write(uint4(gid, 1u), gid.xy, gid.z);
    table.write(table.read(gid.x) + 1.0, gid.x);
    int4 counter = counters.read(gid.xy);
    counters.write(counter + int4(1), gid.xy);
}
