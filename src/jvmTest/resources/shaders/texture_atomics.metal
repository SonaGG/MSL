#include <metal_stdlib>
using namespace metal;

kernel void voxelize(
    texture3d<uint, access::read_write> occupancy [[texture(0)]],
    texture2d<int, access::read_write> depth [[texture(1)]],
    texture2d_array<uint, access::read_write> layers [[texture(2)]],
    texture2d<uint, access::read_write> plain [[texture(3)]],
    device uint* results [[buffer(4)]],
    uint3 id [[thread_position_in_grid]]
) {
    uint4 previous = occupancy.atomic_fetch_or(id, uint4(1u << (id.x & 31u)));
    occupancy.atomic_fetch_and(id, uint4(~(1u << 16)));
    occupancy.atomic_fetch_add(id, uint4(1u));
    occupancy.atomic_fetch_sub(id, uint4(1u));
    occupancy.atomic_fetch_xor(id, uint4(3u));
    occupancy.atomic_fetch_max(id, uint4(previous.x));
    int4 nearest = depth.atomic_fetch_min(id.xy, int4(int(id.z) - 5));
    depth.atomic_fetch_max(id.xy, int4(-1));
    uint4 swapped = layers.atomic_exchange(id.xy, id.z, uint4(7u));
    uint4 expected = uint4(swapped.x);
    bool exchanged = layers.atomic_compare_exchange_weak(id.xy, id.z, &expected, uint4(9u));
    layers.atomic_store(id.xy, id.z + 1u, uint4(2u));
    uint4 loaded = layers.atomic_load(id.xy, id.z);
    plain.write(uint4(loaded.x), id.xy);
    results[id.x] = previous.x + uint(nearest.x) + expected.x + (exchanged ? 1u : 0u) + loaded.x;
}
