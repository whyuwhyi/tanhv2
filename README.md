# TANHFP32 - Hardware Hyperbolic Tangent Function Implementation

A high-performance hardware implementation of the hyperbolic tangent function (tanh) for single-precision floating-point numbers (FP32), designed using Chisel HDL.

## Overview

This project implements **TANHFP32**, which computes `tanh(x)` for FP32 inputs using a pipelined architecture that combines lookup tables (LUT) and piecewise quadratic polynomial approximation to achieve efficient hardware implementation with controlled accuracy trade-offs.

This project reuses the **XiangShan Fudian** floating-point unit library for basic FP32 arithmetic operations (multiplication, addition, fused multiply-add).

## Algorithm

The computation is based on piecewise quadratic polynomial approximation with range-based optimizations:

$$
\begin{equation}
\begin{aligned}
f             &= \tanh(x) \\
\\
\tanh(-x)     &= -\tanh(x) \\
\\
y             &= \tanh(|x|) \\
\\
\text{result} &= \text{sign}(x)\cdot y \\
\\
\text{For } |x|\in[2^{-5},8): \\
\\
\tanh(x)      &= c_0[i] + c_1[i] \cdot |x| + c_2[i] \cdot |x|^2 \\
              &= c_0[i] + |x| \cdot (c_1[i] + c_2[i] \cdot |x|) \\
\\
i             &= (e_{\text{unbias}} + 5)\ll 3 \;\;+\;\; \text{mantissa}[22:20] \\
\\
\text{For } |x|\ge 8: \\
\\
\tanh(x)      &=\text{sign}(x)\cdot 1.0 \\
\\
\text{For } |x| < 2^{-5}: \\
\\
\tanh(x)      &= x
\end{aligned}
\end{equation}
$$

### Key Components

- **Sign extraction**: Compute absolute value $|x|$ and store sign bit for later restoration
- **Exponent and mantissa decomposition**: Decompose $|x|$ into IEEE 754 fields
- **Segment indexing**: Construct segment index directly from $(e_\text{unbias}, \text{mantissa}[22:20])$ - 64 regions total
- **LUT access**: Retrieve $c_0[i], c_1[i], c_2[i]$ coefficients from lookup tables (64 entries each)
- **Quadratic evaluation using Horner's method**:
  - First FMA: $\text{temp} = c_1[i] + c_2[i] \cdot |x|$
  - Second FMA: $y = c_0[i] + |x| \cdot \text{temp}$
- **Coefficient optimization**: Coefficients generated using Minimax approximation to minimize maximum error
- **Sign restoration**: Apply original sign to get final result

## Hardware Design

### Input Filtering

Special value handling and input validation:

- **Special values**: NaN (return NaN), ±Inf (return ±1)
- **Zero**: +0 or -0 → return 0
- **Subnormal numbers**: Return input directly (approximation for small values)
- **Range bypass**: $|x| < 2^{-5}$ returns input, $|x| \ge 8$ returns ±1

### Pipeline Structure

```
S0: Input Filtering and Special Value Handling
    - Special values: is_nan, is_inf, is_zero, is_subnorm
    - Decomposition: sign, exp_field, frac, xAbs
    - Range checks: small_bypass (e_unbias < -5), large_bypass (e_unbias >= 3)
    - Generate bypass signal and bypass_val

S1: Segment Index Construction + LUT Access
    - e_off = e_unbias + 5
    - m_hi3 = frac[22:20]
    - region = (e_off[2:0] << 3) | m_hi3
    - c0 = LUT_c0[region], c1 = LUT_c1[region], c2 = LUT_c2[region]

S2: First FMA Computation - temp = c1 + c2 × xAbs
    - Input: a = xAbs, b = c2, c = c1
    - Output: temp = c1 + c2 × xAbs
    - Uses FCMA (Fused Multiply-Add) pipeline from Fudian library
    - Pipeline depth: MUL(3 stages) + ADD(2 stages)

S3: Second FMA Computation - y = c0 + xAbs × temp
    - Input: a = xAbs, b = temp, c = c0
    - Output: y = c0 + xAbs × (c1 + c2 × xAbs)
    - Uses FCMA (Fused Multiply-Add) pipeline from Fudian library
    - Pipeline depth: MUL(3 stages) + ADD(2 stages)

S4: Sign Restoration + Bypass Multiplexing
    - y_signed = sign ? -y : y
    - result = bypass ? bypass_val : y_signed
```

## Performance Results

### Accuracy Results

Verification on 1,000,000 random test cases:

#### CPU Reference (cmath tanhf)

```
Total:  1,000,000 test cases
Pass:     460,752 (46.08%)
Fail:     539,248 (53.92%)

Average Error: 1.918627e-06
Maximum Error: 3.253741e-04

Average ULP: 28.65
Maximum ULP: 5456

Total Cycles: 1,000,079
Throughput:   1 result/cycle
```

#### GPU Reference (NVIDIA RTX 5060 with -use_fast_math)

```
Total:  1,000,000 test cases
Pass:     556,191 (55.62%)
Fail:     443,809 (44.38%)

Average Error: 1.984894e-06
Maximum Error: 3.307650e-04

Average ULP: 27.82
Maximum ULP: 5545

Total Cycles: 1,000,079
Throughput:   1 result/cycle
```

## Dependencies

### Required

- **Chisel 6.6.0**: Hardware description language
- **Scala 2.13.15**: Programming language for Chisel
- **Mill**: Build tool for Scala/Chisel projects
- **Verilator**: For simulation and verification
- **XiangShan Fudian**: Floating-point arithmetic library (included as git submodule)

### Optional

- **CUDA/NVCC**: For GPU-accelerated reference implementation (NVIDIA GPU required)
- **Synopsys Design Compiler**: For ASIC synthesis (if targeting specific process technology)

## Building

### Initialize Dependencies

```bash
make init
```

This will initialize the XiangShan Fudian submodule.

### Generate SystemVerilog

```bash
# Generate TANHFP32 RTL
./mill --no-server TANHFP32.run
```

The generated SystemVerilog will be placed in `rtl/TANHFP32.sv`.

### Build and Run Simulation

#### CPU Reference (no GPU required)

```bash
make USE_GPU_REF=0 run
```

Uses standard C library `tanhf()` function as the reference.

#### GPU Reference (requires NVIDIA GPU + CUDA)

```bash
make USE_GPU_REF=1 run
```

Uses NVIDIA CUDA math library with `-use_fast_math` flag for hardware-accurate reference.

### Clean Build Artifacts

```bash
make clean
```

## Testing and Verification

### Simulation

Verilator-based testbench with:

- Comprehensive test vector generation (1M test cases)
- Random input generation across full FP32 range
- Special value testing (NaN, Inf, zero, negative numbers, subnormals)
- ULP (Unit in Last Place) error measurement
- Waveform generation (FST format) for debugging

### Reference Models

- **CPU Reference**: Standard C library (`tanhf`)
- **GPU Reference**: NVIDIA CUDA math library with `-use_fast_math` (recommended for hardware comparison)

### Accuracy Metrics

- **ULP Error**: Measures floating-point accuracy in terms of "units in the last place"
- **Absolute Error**: Direct numerical difference between result and reference
- **Pass/Fail**: Bit-exact comparison against reference implementation

## Future Improvements

- [ ] Investigate area/accuracy trade-offs with different segment counts

## Credits

- **XiangShan Fudian FPU Library**: Provides high-quality floating-point arithmetic components
  - Repository: <https://github.com/OpenXiangShan/fudian>
  - Used for: FMUL, FADD, FCMA (Fused Multiply-Add), RawFloat utilities

## References

- IEEE Standard for Floating-Point Arithmetic (IEEE 754-2008)
- XiangShan Fudian FPU: <https://github.com/OpenXiangShan/fudian>
- Chisel/FIRRTL Documentation: <https://www.chisel-lang.org/>
- Handbook of Floating-Point Arithmetic (Muller et al.)
- "Elementary Functions: Algorithms and Implementation" (Muller, 2006)

## License

This project reuses the XiangShan Fudian library. Please refer to the respective license files in the `dependencies/fudian` directory for licensing terms.
