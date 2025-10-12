#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>

constexpr int SAMPLE_RATE = 16000;
constexpr int FRAME_SIZE  = 400;   // 25 ms @16 kHz
constexpr int HOP_SIZE    = 160;   // 10 ms hop
constexpr int MEL_BINS    = 80;
constexpr float PRE_EMPH  = 0.97f;

// Hann window precompute
static float gHann[FRAME_SIZE];
static bool gHannInit = false;

inline void initHann() {
    if (gHannInit) return;
    for (int i = 0; i < FRAME_SIZE; ++i)
        gHann[i] = 0.5f * (1.f - cosf(2.f * M_PI * i / (FRAME_SIZE - 1)));
    gHannInit = true;
}

// IMF filter = high-pass (100 Hz) + pre-emphasis (0.97)
inline void applyIMF(std::vector<float>& x) {
    const float rc = 1.f / (2.f * M_PI * 100.f);
    const float alpha = rc / (rc + 1.f / SAMPLE_RATE);
    float prev = x[0];
    for (size_t i = 1; i < x.size(); ++i) {
        float tmp = x[i];
        // branchless high-pass
        x[i] = alpha * (x[i-1] + tmp - prev);
        prev = tmp;
    }
    // pre-emphasis
    for (size_t i = x.size() - 1; i > 0; --i)
        x[i] -= PRE_EMPH * x[i - 1];
}

// naive DFT magnitude (optimized for 400-sample frame)
inline void computeDFT(const float* frame, std::vector<float>& mag) {
    int N = FRAME_SIZE;
    mag.resize(N / 2 + 1);
    for (int k = 0; k <= N / 2; ++k) {
        float re = 0.f, im = 0.f;
        for (int n = 0; n < N; ++n) {
            float phi = -2.f * M_PI * k * n / N;
            re += frame[n] * cosf(phi);
            im += frame[n] * sinf(phi);
        }
        mag[k] = sqrtf(re * re + im * im);
    }
}

// build Mel filter bank (triangular, log scale)
std::vector<std::vector<float>> buildMelBank() {
    int nfft = FRAME_SIZE / 2 + 1;
    std::vector<std::vector<float>> bank(MEL_BINS, std::vector<float>(nfft, 0.f));

    auto hzToMel = [](float hz) { return 2595.f * log10f(1.f + hz / 700.f); };
    auto melToHz = [](float mel) { return 700.f * (powf(10.f, mel / 2595.f) - 1.f); };

    float melLow  = hzToMel(0.f);
    float melHigh = hzToMel(SAMPLE_RATE / 2.f);
    std::vector<float> melPoints(MEL_BINS + 2);
    for (int i = 0; i < MEL_BINS + 2; ++i)
        melPoints[i] = melToHz(melLow + (melHigh - melLow) * i / (MEL_BINS + 1));

    std::vector<int> bins(MEL_BINS + 2);
    for (int i = 0; i < MEL_BINS + 2; ++i)
        bins[i] = int(floorf((FRAME_SIZE + 1) * melPoints[i] / SAMPLE_RATE));

    for (int m = 1; m <= MEL_BINS; ++m) {
        for (int k = bins[m - 1]; k < bins[m]; ++k)
            bank[m - 1][k] = (k - bins[m - 1]) / float(bins[m] - bins[m - 1]);
        for (int k = bins[m]; k < bins[m + 1]; ++k)
            bank[m - 1][k] = (bins[m + 1] - k) / float(bins[m + 1] - bins[m]);
    }
    return bank;
}

// full MelSpectrogram extraction
std::vector<float> extractMelSpectrogram(const short* samples, int len) {
    initHann();
    std::vector<float> x(len);
    for (int i = 0; i < len; ++i)
        x[i] = samples[i] / 32768.f;

    applyIMF(x);
    auto melBank = buildMelBank();
    int nFrames = 1 + (len - FRAME_SIZE) / HOP_SIZE;

    std::vector<float> melSpec(MEL_BINS * nFrames, 0.f);

    std::vector<float> frame(FRAME_SIZE);
    std::vector<float> mag;
    for (int f = 0; f < nFrames; ++f) {
        int start = f * HOP_SIZE;
        memcpy(frame.data(), &x[start], FRAME_SIZE * sizeof(float));
        for (int i = 0; i < FRAME_SIZE; ++i)
            frame[i] *= gHann[i];

        computeDFT(frame.data(), mag);
        for (int m = 0; m < MEL_BINS; ++m) {
            float sum = 0.f;
            for (int k = 0; k < (int)mag.size(); ++k)
                sum += mag[k] * melBank[m][k];
            melSpec[f * MEL_BINS + m] = logf(sum + 1e-9f);
        }
    }
    return melSpec;
}

