#include <metal_stdlib>
using namespace metal;

int classify(int value)
{
    for (int i = 0; i < 8; ++i) {
        if (value == i * 3) {
            return i;
        }
        if (value < 0) {
            break;
        }
    }
    switch (value) {
        case 1:
        case 2:
            return 100;
        case 5:
            value += 7;
        case 6:
            return value;
        default:
            break;
    }
    int total = 0;
    int k = 0;
    do {
        total += k;
        if (total > 50) continue;
        k++;
    } while (k < 10);
    while (total > 0) {
        total -= 3;
        if (total == 4) return -1;
    }
    return total;
}

template <typename T>
T square(T x)
{
    return x * x;
}

struct Accumulator {
    float sum;
    void add(float value) { sum += value; }
    float mean(uint count) const { return sum / float(count); }
};

kernel void flow(device int* values [[buffer(0)]], device float* results [[buffer(1)]], uint gid [[thread_position_in_grid]])
{
    int v = values[gid];
    Accumulator acc = {0.0};
    acc.add(float(classify(v)));
    acc.add(square(2.0f));
    acc.add(float(square(v)));
    bool flag = v > 3 && values[gid + 1] < 2;
    results[gid] = flag ? acc.mean(3u) : -acc.sum;
    device float* p = results + 1;
    p[gid] = 4.0;
}
