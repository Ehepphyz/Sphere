import tkinter as tk
import random
from matplotlib.figure import Figure
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg

class AsPhyPlotterApp:
    def __init__(self, root):
        self.root = root
        self.root.title("AsPhyEngine - Integrated Workspace Plotter")
        self.root.geometry("700x600")

        # 1. Setup the basic UI frame container
        self.main_frame = tk.Frame(self.root)
        self.main_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

        # Title Label
        self.title_label = tk.Label(
            self.main_frame, 
            text="Embedded Plotter Display Matrix", 
            font=("Arial", 14, "bold")
        )
        self.title_label.pack(side=tk.TOP, pady=5)

        # 2. Initialize the Matplotlib figure surface layout
        # This creates the chart layout without firing up a separate popup window loop
        self.fig = Figure(figsize=(6, 5), dpi=100)
        self.ax = self.fig.add_subplot(111)
        
        # 3. Generate mock star-field target data points
        self.center_ra, self.center_dec = 10.6847, 41.2690
        self.ra_points = [self.center_ra + random.uniform(-0.1, 0.1) for _ in range(12)]
        self.dec_points = [self.center_dec + random.uniform(-0.1, 0.1) for _ in range(12)]

        # 4. Render the data points on the plot axes
        self.render_plot_data()

        # 5. Embed the Matplotlib backend rendering canvas inside Tkinter
        self.canvas = FigureCanvasTkAgg(self.fig, master=self.main_frame)
        self.canvas_widget = self.canvas.get_tk_widget()
        self.canvas_widget.pack(side=tk.TOP, fill=tk.BOTH, expand=True)
        
        # Draw the chart onto the UI framework canvas loop
        self.canvas.draw()

        print("[AsPhyEngine] Application UI initialized. Plotter loaded inside primary frame.")

    def render_plot_data(self):
        # Scatter background observation targets (Blue)
        self.ax.scatter(self.ra_points, self.dec_points, color='blue', s=40, label='Target Stars')
        
        # Crosshair marker representing the synchronized Aladin view center target (Red)
        self.ax.scatter(self.center_ra, self.center_dec, color='red', marker='x', s=120, linewidths=2, label='Aladin Center (M31)')
        
        # Format the axes layout matching standard charting design parameters
        self.ax.set_xlabel("Right Ascension (RA) [degrees]")
        self.ax.set_ylabel("Declination (DEC) [degrees]")
        self.ax.grid(True, linestyle=':', alpha=0.6)
        self.ax.legend(loc='upper right')

# Main execution loop context
if __name__ == "__main__":
    print("[AsPhyEngine] Starting embedded python test application...")
    window = tk.Tk()
    app = AsPhyPlotterApp(window)
    window.mainloop()
    print("[AsPhyEngine] Application loop terminated safely.")