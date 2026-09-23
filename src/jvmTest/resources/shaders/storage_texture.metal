#include <metal_stdlib>
using namespace metal;

kernel void blur(texture2d<float, access::read> source [[texture(0)]],
                 texture2d<half, access::write> destination [[texture(1)]],
                 texture2d_array<float, access::read_write> layers [[texture(2)]],
                 uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= source.get_width() || gid.y >= source.get_height()) {
        return;
    }
    float4 sum = 0.0;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            int2 p = clamp(int2(gid) + int2(dx, dy), int2(0), int2(source.get_width() - 1, source.get_height() - 1));
            sum += source.read(uint2(p));
        }
    }
    destination.write(half4(sum / 9.0), gid);
    float4 layer = layers.read(gid, 1u);
    layers.write(layer * 2.0, gid, 0u);
}
