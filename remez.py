import numpy as np
from scipy.optimize import differential_evolution
import struct

def float_to_hex(f):
    hex_str = hex(struct.unpack('>I', struct.pack('>f', f))[0])
    return hex_str.replace('0x', 'h')  # 改为 Chisel 格式 h

def generate_segments_3bit():
    segments = {}
    x_min, x_max = 2**(-5), 8.0
    
    for exp in range(-5, 3):
        exp_val = 2.0 ** exp
        e_off = exp + 5
        
        for mant_idx in range(8):
            mant_start = mant_idx / 8.0
            mant_end = (mant_idx + 1) / 8.0
            
            a = exp_val * (1.0 + mant_start)
            b = exp_val * (1.0 + mant_end)
            
            region_idx = (e_off << 3) | mant_idx
            
            if b <= x_min or a >= x_max:
                segments[region_idx] = None
                continue
            
            a = max(a, x_min)
            b = min(b, x_max)
            
            if b - a > 1e-9:
                segments[region_idx] = (a, b)
            else:
                segments[region_idx] = None
    
    return segments

def minimax_fit(a, b):
    x_init = np.linspace(a, b, 500)
    y_init = np.tanh(x_init)
    A = np.vstack([np.ones_like(x_init), x_init, x_init**2]).T
    c_init = np.linalg.lstsq(A, y_init, rcond=None)[0]
    
    def objective(c):
        x_dense = np.linspace(a, b, 3000)
        y_true = np.tanh(x_dense)
        y_approx = c[0] + c[1]*x_dense + c[2]*x_dense**2
        return np.max(np.abs(y_true - y_approx))
    
    bounds = [
        (c_init[0] - 0.1, c_init[0] + 0.1),
        (c_init[1] - 0.2, c_init[1] + 0.2),
        (c_init[2] - 0.2, c_init[2] + 0.2)
    ]
    
    result = differential_evolution(
        objective,
        bounds,
        strategy='best1bin',
        maxiter=500,
        popsize=20,
        atol=1e-14,
        tol=1e-14,
        seed=42,
        workers=1,
        polish=True
    )
    
    return result.x

def compute_coefficients():
    segments = generate_segments_3bit()
    target = 2**(-12)
    
    results = {}
    max_errors = []
    valid_count = 0
    
    print(f"Total segments: 64")
    
    for idx in range(64):
        if segments[idx] is None:
            results[idx] = {
                'c0': 0.0,
                'c1': 0.0,
                'c2': 0.0
            }
            continue
        
        valid_count += 1
        a, b = segments[idx]
        
        c = minimax_fit(a, b)
        
        x_test = np.linspace(a, b, 10000)
        y_true = np.tanh(x_test)
        y_approx = c[0] + c[1]*x_test + c[2]*x_test**2
        max_err = np.max(np.abs(y_true - y_approx))
        max_errors.append(max_err)
        
        results[idx] = {
            'c0': c[0],
            'c1': c[1],
            'c2': c[2]
        }
        
        if valid_count % 10 == 0:
            print(f"  Valid segments: {valid_count}")
    
    overall_max = max(max_errors) if max_errors else 0
    print(f"\nValid segments: {valid_count}/64")
    print(f"Max error: {overall_max:.10e}")
    print(f"Target:    {target:.10e}")
    print(f"Status:    {'PASS' if overall_max < target else 'FAIL'}")
    
    return results

def save_to_file(results, filename="lut.txt"):
    with open(filename, 'w') as f:
        for idx in range(64):
            r = results[idx]
            c0_hex = float_to_hex(r['c0'])
            c1_hex = float_to_hex(r['c1'])
            c2_hex = float_to_hex(r['c2'])
            f.write(f"{idx} {c0_hex} {c1_hex} {c2_hex}\n")
    
    print(f"\nSaved to {filename}")
    print(f"Entries: 64")
    print(f"Size: {64 * 3 * 4} bytes")

if __name__ == "__main__":
    results = compute_coefficients()
    save_to_file(results, "lut.txt")
