#include <metal_stdlib>
using namespace metal;

struct Segment {
    float4 value;
    uint next;
};

float sumRange(device const float* begin, device const float* end)
{
    float total = 0.0;
    for (device const float* it = begin; it != end; ++it) {
        total += *it;
    }
    return total;
}

kernel void walk(device const float* input [[buffer(0)]],
                 device float* output [[buffer(1)]],
                 device Segment* segments [[buffer(2)]],
                 constant uint& count [[buffer(3)]],
                 uint gid [[thread_position_in_grid]])
{
    device const float* cursor = gid % 2 == 0 ? input : input + 4;
    float value = sumRange(cursor, cursor + count);
    device float* target = output;
    for (uint i = 0; i < gid; ++i) {
        target += 2;
    }
    *target = value;
    device Segment* segment = segments + gid;
    if (segment->next != 0u) {
        segment = segments + segment->next;
    }
    segment->value += float4(value);
    float local[8];
    for (uint i = 0; i < 8; ++i) {
        local[i] = float(i) * value;
    }
    thread float* pick = value > 1.0 ? &local[1] : &local[5];
    output[gid + 1] = pick[1];
}
