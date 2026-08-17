import os
import random
import matplotlib

# Force headless background image file creation
matplotlib.use('Agg')
import matplotlib.pyplot as plt

def main():
    print("[AsPhyEngine] Headless image plotting routine initiated...")
    
    # 1. Target coordinate datasets
    center_ra, center_dec = 10.6847, 41.2690
    ra_points = [center_ra + random.uniform(-0.1, 0.1) for _ in range(15)]
    dec_points = [center_dec + random.uniform(-0.1, 0.1) for _ in range(15)]

    # 2. Build the chart structure layout canvas
    fig, ax = plt.subplots(figsize=(6, 5), dpi=100)
    ax.scatter(ra_points, dec_points, color='#1f77b4', s=50, alpha=0.8, label='Catalog Targets')
    ax.scatter(center_ra, center_dec, color='#d62728', marker='x', s=150, linewidths=2, label='Aladin Center')

    ax.set_title("AsPhyEngine - Target Acquisition Map", fontsize=12, pad=10)
    ax.set_xlabel("Right Ascension (RA) [degrees]")
    ax.set_ylabel("Declination (DEC) [degrees]")
    ax.grid(True, linestyle=':', alpha=0.6)
    ax.legend(loc='upper right')

    # 3. Save the file to the parent runtime folder where Java is running
    # This goes up one level out of 'snippets/' to drop the image right in the project root
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    output_filepath = os.path.join(project_root, "output.png")

    plt.savefig(output_filepath, bbox_inches='tight', dpi=120)
    plt.close(fig)
    print(f"[AsPhyEngine] Success! Image file generated at: {output_filepath}")

if __name__ == "__main__":
    main()