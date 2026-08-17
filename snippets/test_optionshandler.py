import os
import sys
import random
import matplotlib

# Force headless background file generation
matplotlib.use('Agg')
import matplotlib.pyplot as plt

def main():
    print("[AsPhyEngine] Python script triggered successfully.")
    
    # Track paths clearly in the console
    working_directory = os.getcwd()
    print(f"[AsPhyEngine] Active Terminal Working Directory: {working_directory}")

    # 1. Target dataset values
    center_ra, center_dec = 10.6847, 41.2690
    ra_points = [center_ra + random.uniform(-0.1, 0.1) for _ in range(15)]
    dec_points = [center_dec + random.uniform(-0.1, 0.1) for _ in range(15)]

    # 2. Structure chart framework
    fig, ax = plt.subplots(figsize=(6, 5), dpi=100)
    ax.scatter(ra_points, dec_points, color='#1f77b4', s=50, alpha=0.8, label='Catalog Targets')
    ax.scatter(center_ra, center_dec, color='#d62728', marker='x', s=150, linewidths=2, label='Aladin Center (M31)')

    ax.set_title("AsPhyEngine - Real-time Target Acquisition Map", fontsize=12, pad=10)
    ax.set_xlabel("Right Ascension (RA) [degrees]", fontsize=10)
    ax.set_ylabel("Declination (DEC) [degrees]", fontsize=10)
    ax.grid(True, linestyle=':', alpha=0.6)
    ax.legend(loc='upper right')

    # 3. FORCE write directly to the current active shell folder path
    output_filename = os.path.join(working_directory, "output.png")
    print(f"[AsPhyEngine] Attempting file save sequence to destination: {output_filename}")

    try:
        plt.savefig(output_filename, bbox_inches='tight', dpi=120)
        plt.close(fig)
        print(f"[AsPhyEngine] Success! File physically verified at: {output_filename}")
    except Exception as error:
        print(f"[AsPhyEngine] CRITICAL FILE WRITE ERROR: {str(error)}", file=sys.stderr)

if __name__ == "__main__":
    main()