package com.sphere.components.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the editor knows of the libraries a physicist calls every day: ROOT's
 * classes, the C++ standard library, numpy, matplotlib, uproot, awkward. It
 * answers "what can follow h->" once the editor has read that h is a TH1F,
 * without a language server and without a network.
 */
public final class KnowledgeBase {

    private KnowledgeBase() {
    }

    public static final class Member {
        public final String owner;
        public final String name;
        /** "(x, w=1)" for a call, "" for a field or a constant. */
        public final String params;
        /** Type of the result when it matters for chaining, else "". */
        public final String returns;
        public final String doc;

        Member(String owner, String name, String params, String returns, String doc) {
            this.owner = owner;
            this.name = name;
            this.params = params;
            this.returns = returns;
            this.doc = doc;
        }

        public boolean isCallable() {
            return !params.isEmpty();
        }

        public String signature() {
            return name + params + (returns.isEmpty() ? "" : " → " + returns);
        }
    }

    private static final Map<String, List<Member>> MEMBERS = new HashMap<>();
    private static final Map<String, List<String>> PARENTS = new HashMap<>();

    /** Globals ROOT declares, and the class each one is. */
    public static final Map<String, String> ROOT_GLOBALS = Map.of(
            "gStyle", "TStyle", "gPad", "TPad", "gROOT", "TROOT", "gRandom", "TRandom",
            "gDirectory", "TDirectory", "gFile", "TFile", "gSystem", "TSystem");

    /** Python modules by the name they are imported under. */
    private static final Map<String, String> MODULES = Map.ofEntries(
            Map.entry("numpy", "numpy"),
            Map.entry("matplotlib.pyplot", "pyplot"),
            Map.entry("ROOT", "ROOT"),
            Map.entry("uproot", "uproot"),
            Map.entry("awkward", "awkward"),
            Map.entry("math", "math"),
            Map.entry("os", "os"),
            Map.entry("os.path", "os.path"),
            Map.entry("sys", "sys"),
            Map.entry("scipy.optimize", "scipy.optimize"),
            Map.entry("pandas", "pandas"),
            Map.entry("vector", "vector"),
            Map.entry("hist", "hist"));

    private static final String DATA = """
@TObject
GetName() -> const char* : The object's name
GetTitle() -> const char* : The object's title
Draw(option="") : Draws the object in the current pad
Print(option="") : Prints a summary of the object
Write(name=0, option=0) -> Int_t : Writes the object into the current directory
Clone(newname="") -> TObject : A deep copy of the object
InheritsFrom(classname) -> Bool_t : Whether the object derives from a class
ClassName() -> const char* : Name of the object's class
SaveAs(filename, option="") : Saves the object in a file (.png, .pdf, .root, .C)
@TNamed TObject
SetName(name) : Changes the name
SetTitle(title) : Changes the title
@TH1 TNamed
Fill(x, w=1) -> Int_t : Increments the bin containing x by the weight w
FillRandom(fname, ntimes=5000) : Fills with random numbers drawn from a function
Draw(option="") : Draws the histogram: "HIST", "E", "SAME", "COLZ", "LEGO"...
DrawCopy(option="") -> TH1 : Draws a copy that can change without touching the original
DrawNormalized(option="", norm=1) -> TH1 : Draws a copy scaled to an integral of norm
GetEntries() -> Double_t : Number of entries (calls of Fill)
GetEffectiveEntries() -> Double_t : Entries that would give the same errors with unit weights
GetMean(axis=1) -> Double_t : Mean along an axis
GetStdDev(axis=1) -> Double_t : Standard deviation along an axis
GetRMS(axis=1) -> Double_t : Same as GetStdDev
GetMeanError(axis=1) -> Double_t : Error on the mean
Integral(binx1, binx2, option="") -> Double_t : Sum of the bin contents ("width" multiplies by bin widths)
GetSumOfWeights() -> Double_t : Sum of the in-range bin contents
Scale(c1=1, option="") : Multiplies every bin by c1 ("width" divides by bin widths)
Rebin(ngroup=2, newname="") -> TH1 : Merges ngroup adjacent bins
Sumw2(flag=true) : Stores the sum of squared weights, for correct errors
Fit(fname, option="", goption="", xmin=0, xmax=0) -> TFitResultPtr : Fits a function ("gaus", "expo", "pol1"...)
GetFunction(name) -> TF1 : A function attached by Fit
GetBinContent(bin) -> Double_t : Content of a bin (0 is underflow, nbins+1 overflow)
SetBinContent(bin, content) : Sets the content of a bin
GetBinError(bin) -> Double_t : Error of a bin
SetBinError(bin, error) : Sets the error of a bin
GetBinCenter(bin) -> Double_t : Center of a bin
GetBinLowEdge(bin) -> Double_t : Lower edge of a bin
GetBinWidth(bin) -> Double_t : Width of a bin
GetNbinsX() -> Int_t : Number of bins along x
FindBin(x, y=0, z=0) -> Int_t : The bin containing x
GetXaxis() -> TAxis : The x axis
GetYaxis() -> TAxis : The y axis
GetZaxis() -> TAxis : The z axis
GetMaximum(maxval=FLT_MAX) -> Double_t : Largest bin content
GetMinimum(minval=-FLT_MAX) -> Double_t : Smallest bin content
GetMaximumBin() -> Int_t : The bin holding the maximum
SetMaximum(maximum) : Upper limit of the y range when drawn
SetMinimum(minimum) : Lower limit of the y range when drawn
SetAxisRange(xmin, xmax, axis="X") : Restricts the range shown and used
SetLineColor(color) : Line color (kRed, kBlue, TColor::GetColor...)
SetLineWidth(width) : Line width in pixels
SetLineStyle(style) : 1 solid, 2 dashed, 3 dotted...
SetFillColor(color) : Fill color
SetFillStyle(style) : 1001 solid, 3004 hatched, 0 hollow...
SetMarkerStyle(style) : 20 full circle, 21 full square, 24 open circle...
SetMarkerColor(color) : Marker color
SetMarkerSize(size) : Marker size
SetStats(stats=true) : Shows or hides the statistics box
SetDirectory(dir) : Detaches (nullptr) or attaches the histogram to a directory
Add(h1, c1=1) -> Bool_t : Adds c1 times another histogram
Divide(h1) -> Bool_t : Divides bin by bin
Multiply(h1) -> Bool_t : Multiplies bin by bin
Reset(option="") : Empties the histogram
Smooth(ntimes=1) : Smooths the contents
KolmogorovTest(h2, option="") -> Double_t : Kolmogorov compatibility probability
Chi2Test(h2, option="UU") -> Double_t : Chi-square compatibility probability
GetQuantiles(nprobSum, q, probSum=0) -> Int_t : Quantiles of the distribution
ShowBackground(niter=20, option="same") -> TH1 : Estimated background (TSpectrum)
@TH1F TH1
@TH1D TH1
@TH1I TH1
@TProfile TH1
GetBinEntries(bin) -> Double_t : Entries in a bin of the profile
ProjectionX(name="_px", option="e") -> TH1D : The profile as a histogram
@TH2 TH1
Fill(x, y, w=1) -> Int_t : Increments the bin containing (x, y)
GetNbinsY() -> Int_t : Number of bins along y
GetBinContent(binx, biny) -> Double_t : Content of a cell
ProjectionX(name="_px", firstybin=0, lastybin=-1, option="") -> TH1D : Projection on x
ProjectionY(name="_py", firstxbin=0, lastxbin=-1, option="") -> TH1D : Projection on y
ProfileX(name="_pfx", firstybin=1, lastybin=-1, option="") -> TProfile : Profile along x
ProfileY(name="_pfy", firstxbin=1, lastxbin=-1, option="") -> TProfile : Profile along y
GetCorrelationFactor(axis1=1, axis2=2) -> Double_t : Correlation between the axes
@TH2F TH2
@TH2D TH2
@TH3F TH2
@TH3D TH2
@TAxis TNamed
SetTitle(title) : Axis title (TLatex: "p_{T} [GeV]")
SetRangeUser(ufirst, ulast) : Range in user coordinates
SetRange(first=0, last=0) : Range in bin numbers
SetTitleSize(size=0.04) : Size of the title
SetTitleOffset(offset=1) : Distance of the title from the axis
SetLabelSize(size=0.04) : Size of the labels
SetNdivisions(n=510, optim=true) : Number of divisions
CenterTitle(center=true) : Centers the title
SetMoreLogLabels(more=true) : More labels on a log axis
SetNoExponent(noExponent=true) : Labels without the 10^n factor
SetBinLabel(bin, label) : Text label of a bin
GetXmin() -> Double_t : Low edge of the axis
GetXmax() -> Double_t : High edge of the axis
GetNbins() -> Int_t : Number of bins
FindBin(x) -> Int_t : Bin containing x
GetBinCenter(bin) -> Double_t : Center of a bin
@TCanvas TPad
@TPad TObject
cd(subpadnumber=0) -> TVirtualPad : Makes the pad (or one of its sub-pads) current
Divide(nx=1, ny=1, xmargin=0.01, ymargin=0.01) : Splits into nx by ny sub-pads
Update() : Redraws what changed
Modified(flag=true) : Marks the pad as needing a redraw
Clear(option="") : Removes everything drawn
Close(option="") : Closes the canvas
SetLogx(value=1) : Logarithmic x axis
SetLogy(value=1) : Logarithmic y axis
SetLogz(value=1) : Logarithmic z axis (color scale)
SetGrid(valuex=1, valuey=1) : Grid lines
SetGridx(value=1) : Vertical grid lines
SetGridy(value=1) : Horizontal grid lines
SetTicks(valuex=1, valuey=1) : Ticks on the opposite axes
SetLeftMargin(margin) : Left margin as a fraction of the pad
SetRightMargin(margin) : Right margin (room for COLZ palettes)
SetBottomMargin(margin) : Bottom margin
SetTopMargin(margin) : Top margin
SetMargin(left, right, bottom, top) : All four margins
SetWindowSize(ww, wh) : Size of the window
GetPad(subpadnumber) -> TVirtualPad : A sub-pad
GetPrimitive(name) -> TObject : An object drawn in the pad
Print(filename="", option="") : Writes the pad to a file (.png, .pdf, .svg, .C)
SaveAs(filename="", option="") : Writes the pad to a file
BuildLegend(x1=0.3, y1=0.21, x2=0.3, y2=0.21, title="") -> TLegend : A legend of what is drawn
@TVirtualPad TPad
@TDirectory TNamed
Get(namecycle) -> TObject : Reads an object by name ("h1", "dir/h1;2")
cd(path=nullptr) -> Bool_t : Makes this directory current
ls(option="") : Lists the contents
mkdir(name, title="") -> TDirectory : Creates a sub-directory
GetListOfKeys() -> TList : The keys stored in the directory
@TFile TDirectory
Open(name, option="", ftitle="", compress=1) -> TFile : Opens a file: "READ", "RECREATE", "UPDATE", or a URL
Close(option="") : Closes the file
Write(name=0, opt=0) -> Int_t : Writes every object of the directory
IsOpen() -> Bool_t : Whether the file is open
IsZombie() -> Bool_t : Whether opening failed
GetSize() -> Long64_t : Size in bytes
ReadAll(option="") : Reads every object into memory
@TTree TNamed
Branch(name, address, leaflist="") -> TBranch : Creates a branch ("x/D", "n/I", "v[n]/F")
Fill() -> Int_t : Adds the current values of all branches as an entry
GetEntry(entry=0, getall=0) -> Int_t : Reads an entry into the branch addresses
GetEntries() -> Long64_t : Number of entries
SetBranchAddress(bname, address) -> Int_t : Where GetEntry writes a branch
SetBranchStatus(bname, status=1) : Enables or disables branches ("*" for all)
Draw(varexp, selection="", option="", nentries=-1, firstentry=0) -> Long64_t : Draws an expression ("pt", "pt:eta", "pt>>h(100,0,10)")
Project(hname, varexp, selection="") -> Long64_t : Fills an existing histogram
Scan(varexp="", selection="") -> Long64_t : Prints entries as a table
Print(option="") : Lists the branches and their sizes
Show(entry=-1) : Prints the values of one entry
AddFriend(treename, filename="") -> TFriendElement : Adds the columns of another tree
GetBranch(name) -> TBranch : A branch by name
GetLeaf(name) -> TLeaf : A leaf by name
GetListOfBranches() -> TObjArray : The branches
CloneTree(nentries=-1, option="") -> TTree : A copy, possibly with selected entries
MakeClass(classname=0) -> Int_t : Writes a skeleton class reading this tree
LoadTree(entry) -> Long64_t : Sets the current entry (TChain: the current file)
SetEntryList(list) : Restricts the entries processed
@TChain TTree
Add(name, nentries=TTree::kMaxEntries) -> Int_t : Adds files ("data/*.root", "file.root/tree")
GetNtrees() -> Int_t : Number of files
Process(selector, option="") -> Long64_t : Runs a TSelector over the chain
@TNtuple TTree
Fill(x0, x1, x2) -> Int_t : Adds a row of values
@TGraph TNamed
SetPoint(i, x, y) : Sets point i
AddPoint(x, y) : Appends a point
GetPoint(i, x, y) -> Int_t : Reads point i into x and y
GetN() -> Int_t : Number of points
GetX() -> Double_t* : The x values
GetY() -> Double_t* : The y values
Eval(x, spline=nullptr, option="") -> Double_t : Interpolated value at x
Draw(option="") : Draws: "AP" axis and points, "AL" line, "ACP" curve...
Fit(fname, option="", goption="", xmin=0, xmax=0) -> TFitResultPtr : Fits a function
GetFunction(name) -> TF1 : A function attached by Fit
GetXaxis() -> TAxis : The x axis (after drawing)
GetYaxis() -> TAxis : The y axis (after drawing)
GetHistogram() -> TH1F : The frame histogram
SetMarkerStyle(style) : Marker style
SetMarkerColor(color) : Marker color
SetMarkerSize(size) : Marker size
SetLineColor(color) : Line color
SetLineWidth(width) : Line width
SetFillColor(color) : Fill color
RemovePoint(ipoint) -> Int_t : Removes a point
GetMean(axis=1) -> Double_t : Mean of the x (1) or y (2) values
GetRMS(axis=1) -> Double_t : RMS of the x (1) or y (2) values
Sort() : Sorts the points by x
@TGraphErrors TGraph
SetPointError(i, ex, ey) : Errors of point i
GetErrorX(i) -> Double_t : x error of point i
GetErrorY(i) -> Double_t : y error of point i
@TGraphAsymmErrors TGraph
SetPointError(i, exl, exh, eyl, eyh) : Asymmetric errors of point i
Divide(pass, total, option="cp") : Efficiency graph from two histograms
@TMultiGraph TNamed
Add(graph, chopt="") : Adds a graph
Draw(option="") : Draws all the graphs ("A" for axes)
GetXaxis() -> TAxis : The x axis
GetYaxis() -> TAxis : The y axis
@THStack TNamed
Add(h, option="") : Stacks a histogram
Draw(option="") : Draws the stack ("nostack" to overlay)
GetHistogram() -> TH1 : The frame histogram
GetXaxis() -> TAxis : The x axis
@TF1 TNamed
SetParameter(ipar, value) : Sets a parameter
SetParameters(p0, p1, p2) : Sets the first parameters
GetParameter(ipar) -> Double_t : Value of a parameter (after a fit, the fitted value)
GetParError(ipar) -> Double_t : Error of a fitted parameter
SetParName(ipar, name) : Names a parameter
SetParNames(name0, name1, name2) : Names the parameters
SetParLimits(ipar, parmin, parmax) : Bounds of a parameter during fits
FixParameter(ipar, value) : Freezes a parameter
Eval(x, y=0, z=0, t=0) -> Double_t : Value at x
Integral(a, b, epsrel=1e-12) -> Double_t : Integral from a to b
Derivative(x) -> Double_t : Derivative at x
GetChisquare() -> Double_t : Chi-square of the last fit
GetNDF() -> Int_t : Degrees of freedom of the last fit
GetProb() -> Double_t : Probability of the last fit's chi-square
GetMaximum(xmin=0, xmax=0) -> Double_t : Maximum over a range
GetMaximumX(xmin=0, xmax=0) -> Double_t : Where the maximum is
GetMinimum(xmin=0, xmax=0) -> Double_t : Minimum over a range
GetX(y, xmin=0, xmax=0) -> Double_t : The x where the function equals y
GetRandom() -> Double_t : A random number following the function
SetRange(xmin, xmax) : Domain of the function
SetNpx(npx=100) : Points used to draw it
SetLineColor(color) : Line color
@TF2 TF1
@TLegend TObject
AddEntry(obj, label="", option="lpf") -> TLegendEntry : Adds an entry: "l" line, "p" marker, "f" fill
SetHeader(header="", option="") : Title of the legend
SetBorderSize(size=4) : Border width (0 for none)
SetFillColor(color) : Background color
SetFillStyle(style) : Background style (0 transparent)
SetTextSize(size) : Text size
SetTextFont(font) : Text font (42 Helvetica)
SetNColumns(nColumns) : Number of columns
Draw(option="") : Draws the legend
Clear(option="") : Removes the entries
@TLatex TObject
DrawLatex(x, y, text) -> TLatex : Draws text at (x, y)
DrawLatexNDC(x, y, text) -> TLatex : Draws text in pad coordinates 0..1
SetTextSize(size) : Text size
SetTextAlign(align) : Alignment: 11 left-bottom, 22 centered...
SetNDC(isNDC=true) : Coordinates relative to the pad
@TLorentzVector TObject
Pt() -> Double_t : Transverse momentum
Eta() -> Double_t : Pseudorapidity
Phi() -> Double_t : Azimuth, in [-π, π]
M() -> Double_t : Invariant mass
E() -> Double_t : Energy
Px() -> Double_t : x component
Py() -> Double_t : y component
Pz() -> Double_t : z component
P() -> Double_t : Magnitude of the momentum
Et() -> Double_t : Transverse energy
Mt() -> Double_t : Transverse mass
M2() -> Double_t : Invariant mass squared
Rapidity() -> Double_t : Rapidity
Theta() -> Double_t : Polar angle
CosTheta() -> Double_t : Cosine of the polar angle
Beta() -> Double_t : Velocity p/E
Gamma() -> Double_t : Lorentz factor
Perp() -> Double_t : Same as Pt
SetPtEtaPhiM(pt, eta, phi, m) : Sets from pt, η, φ and the mass
SetPtEtaPhiE(pt, eta, phi, e) : Sets from pt, η, φ and the energy
SetPxPyPzE(px, py, pz, e) : Sets the four components
SetXYZM(x, y, z, m) : Sets from the momentum and the mass
DeltaR(v) -> Double_t : √(Δη² + Δφ²) to another vector
DeltaPhi(v) -> Double_t : Δφ to another vector, in [-π, π]
Angle(v3) -> Double_t : Angle between the momenta
Boost(bx, by, bz) : Lorentz boost
BoostVector() -> TVector3 : The velocity vector β
Vect() -> TVector3 : The spatial part
Dot(q) -> Double_t : Minkowski product
@TVector3 TObject
X() -> Double_t : x
Y() -> Double_t : y
Z() -> Double_t : z
Mag() -> Double_t : Length
Mag2() -> Double_t : Length squared
Perp() -> Double_t : Transverse component
Phi() -> Double_t : Azimuth
Theta() -> Double_t : Polar angle
Eta() -> Double_t : Pseudorapidity
Unit() -> TVector3 : Unit vector
Dot(v) -> Double_t : Scalar product
Cross(v) -> TVector3 : Vector product
Angle(v) -> Double_t : Angle to another vector
DeltaR(v) -> Double_t : √(Δη² + Δφ²)
SetXYZ(x, y, z) : Sets the components
SetPtEtaPhi(pt, eta, phi) : Sets from pt, η, φ
RotateX(angle) : Rotation about x
RotateY(angle) : Rotation about y
RotateZ(angle) : Rotation about z
@TRandom TNamed
Gaus(mean=0, sigma=1) -> Double_t : Gaussian number
Uniform(x1=0, x2=1) -> Double_t : Uniform number in [x1, x2)
Rndm() -> Double_t : Uniform number in (0, 1]
Poisson(mean) -> Int_t : Poisson number
Exp(tau) -> Double_t : Exponential number
Landau(mean=0, sigma=1) -> Double_t : Landau number
BreitWigner(mean=0, gamma=1) -> Double_t : Breit-Wigner number
Binomial(ntot, prob) -> Int_t : Binomial number
Integer(imax) -> UInt_t : Integer in [0, imax)
Rannor(a, b) : Two Gaussian numbers at once
Sphere(x, y, z, r) : Point on a sphere of radius r
SetSeed(seed=0) : Seed (0 picks one from the clock)
GetSeed() -> UInt_t : The seed
@TRandom3 TRandom
@TStyle TNamed
SetOptStat(mode=1) : Statistics box: 0 none, 1111 name/entries/mean/RMS, "nemr"
SetOptFit(mode=1) : Fit box: 1111 probability/chi2/errors/values
SetOptTitle(tit=1) : Shows the histogram titles
SetPalette(ncolors=kBird, colors=0) : Color palette (kViridis, kRainBow...)
SetNumberContours(number=20) : Shades in COLZ plots
SetTitleSize(size=0.02, axis="X") : Size of axis titles
SetLabelSize(size=0.04, axis="X") : Size of axis labels
SetPadTickX(tickx) : Ticks on the top axis
SetPadTickY(ticky) : Ticks on the right axis
SetPadLeftMargin(margin=0.1) : Left margin of every pad
SetPadBottomMargin(margin=0.1) : Bottom margin of every pad
SetCanvasColor(color=19) : Canvas background
SetFrameBorderMode(mode=1) : Frame border
SetLegendBorderSize(size=4) : Legend border
SetMarkerStyle(mstyle=1) : Default marker
SetHistLineWidth(width=1) : Default histogram line width
@TROOT TDirectory
SetBatch(batch=true) : Draws without opening windows
ProcessLine(line, error=0) -> Long_t : Runs a line of C++ in the interpreter
LoadMacro(filename, error=0, check=false) -> Int_t : Loads a macro ("macro.C+" compiles it)
Macro(filename, error=0, padUpdate=true) -> Long_t : Runs a macro
GetListOfFiles() -> TSeqCollection : Open files
FindObject(name) -> TObject : An object anywhere in memory
SetStyle(stylename="Default") : Switches to a TStyle
ForceStyle(force=true) : Applies the style to objects read from files
GetVersion() -> const char* : ROOT version
@TSystem TNamed
Exec(shellcmd) -> Int_t : Runs a shell command
Load(module, entry="", system=false) -> Int_t : Loads a library
Getenv(env) -> const char* : An environment variable
Setenv(name, value) : Sets an environment variable
AccessPathName(path, mode=kFileExists) -> Bool_t : true when the path does NOT exist
mkdir(name, recursive=false) -> Int_t : Creates a directory
pwd() -> const char* : Working directory
@RDataFrame
Filter(expression, name="") -> RDataFrame : Keeps the entries passing a cut ("pt > 20")
Define(name, expression) -> RDataFrame : Adds a computed column
Redefine(name, expression) -> RDataFrame : Replaces a column
Alias(alias, column) -> RDataFrame : Another name for a column
Range(begin, end, stride=1) -> RDataFrame : A slice of the entries
Histo1D(model, column, weight="") -> TH1D : Fills a 1D histogram lazily (("h","title",100,0,10), "x")
Histo2D(model, columnx, columny) -> TH2D : Fills a 2D histogram lazily
Profile1D(model, columnx, columny) -> TProfile : Fills a profile lazily
Graph(columnx, columny) -> TGraph : A graph of two columns
Count() -> ULong64_t : Number of entries (lazy)
Sum(column) -> double : Sum of a column (lazy)
Mean(column) -> double : Mean of a column (lazy)
Min(column) -> double : Minimum of a column (lazy)
Max(column) -> double : Maximum of a column (lazy)
Take(column) -> std::vector : Every value of a column
AsNumpy(columns) -> dict : Columns as numpy arrays (Python)
Snapshot(treename, filename, columns) -> RDataFrame : Writes the selected entries to a new file
Foreach(f, columns) : Calls f on every entry
Display(columns, nRows=5) : A printable table of the first rows
Report() : Efficiency of every named Filter
Describe() : Columns, types and the computation graph
GetColumnNames() -> std::vector : Names of the columns
Vary(column, expression, variationTags) -> RDataFrame : Systematic variations
@ROOT
RDataFrame(treename, filename) -> RDataFrame : Columnar analysis of a tree
EnableImplicitMT(numthreads=0) : Multi-threaded RDataFrame and TTree reading
DisableImplicitMT() : Single-threaded again
TH1F(name, title, nbins, xlow, xup) -> TH1F : 1D histogram of floats
TH1D(name, title, nbins, xlow, xup) -> TH1D : 1D histogram of doubles
TH2F(name, title, nbinsx, xlow, xup, nbinsy, ylow, yup) -> TH2F : 2D histogram
TH2D(name, title, nbinsx, xlow, xup, nbinsy, ylow, yup) -> TH2D : 2D histogram of doubles
TProfile(name, title, nbins, xlow, xup) -> TProfile : Profile histogram
TCanvas(name, title, ww=700, wh=500) -> TCanvas : A drawing window
TFile(name, option="") -> TFile : A ROOT file
TTree(name, title) -> TTree : A tree
TChain(name) -> TChain : Several trees as one
TGraph(n, x, y) -> TGraph : A graph of n points
TGraphErrors(n, x, y, ex, ey) -> TGraphErrors : A graph with errors
TF1(name, formula, xmin, xmax) -> TF1 : A function ("gaus", "[0]*exp(-x/[1])")
TLegend(x1, y1, x2, y2) -> TLegend : A legend in pad coordinates
TLatex() -> TLatex : Text with formulas
TLorentzVector(px, py, pz, e) -> TLorentzVector : A four-vector
TVector3(x, y, z) -> TVector3 : A three-vector
TRandom3(seed=4357) -> TRandom3 : Mersenne Twister generator
THStack(name, title) -> THStack : Stacked histograms
gROOT -> TROOT : The ROOT session
gStyle -> TStyle : The current style
gPad -> TPad : The current pad
gRandom -> TRandom : The default random generator
gSystem -> TSystem : The operating system
kRed -> Color_t : Red
kBlue -> Color_t : Blue
kGreen -> Color_t : Green
kBlack -> Color_t : Black
kOrange -> Color_t : Orange
kMagenta -> Color_t : Magenta
kAzure -> Color_t : Azure
VecOps -> namespace : RVec and its functions
Math -> namespace : GenVector, special functions, minimizers
@TMath
Pi() -> Double_t : π
TwoPi() -> Double_t : 2π
PiOver2() -> Double_t : π/2
E() -> Double_t : e
C() -> Double_t : Speed of light, m/s
H() -> Double_t : Planck constant, J·s
Hbar() -> Double_t : Reduced Planck constant, J·s
Qe() -> Double_t : Elementary charge, C
Na() -> Double_t : Avogadro number
K() -> Double_t : Boltzmann constant, J/K
Sqrt(x) -> Double_t : Square root
Power(x, y) -> Double_t : x to the power y
Exp(x) -> Double_t : Exponential
Log(x) -> Double_t : Natural logarithm
Log10(x) -> Double_t : Base-10 logarithm
Abs(x) -> Double_t : Absolute value
Sin(x) -> Double_t : Sine
Cos(x) -> Double_t : Cosine
Tan(x) -> Double_t : Tangent
ATan2(y, x) -> Double_t : Arc tangent of y/x, in [-π, π]
Hypot(x, y) -> Double_t : √(x² + y²)
Gaus(x, mean=0, sigma=1, norm=false) -> Double_t : Gaussian
Landau(x, mpv=0, sigma=1, norm=false) -> Double_t : Landau density
BreitWigner(x, mean=0, gamma=1) -> Double_t : Breit-Wigner density
Poisson(x, par) -> Double_t : Poisson probability
Prob(chi2, ndf) -> Double_t : Probability of a chi-square
Erf(x) -> Double_t : Error function
Erfc(x) -> Double_t : Complementary error function
Floor(x) -> Double_t : Floor
Ceil(x) -> Double_t : Ceiling
Min(a, b) -> Double_t : Smaller of two values
Max(a, b) -> Double_t : Larger of two values
Mean(n, a) -> Double_t : Mean of an array
RMS(n, a) -> Double_t : RMS of an array
Median(n, a) -> Double_t : Median of an array
DegToRad() -> Double_t : π/180
RadToDeg() -> Double_t : 180/π
@std
vector -> class : Dynamic array
string -> class : Character string
string_view -> class : Read-only view of a string
map -> class : Sorted associative container
unordered_map -> class : Hash map
set -> class : Sorted set
unordered_set -> class : Hash set
array -> class : Fixed-size array
pair -> class : Two values
tuple -> class : Several values
optional -> class : A value or nothing
variant -> class : One of several types
function -> class : Callable wrapper
unique_ptr -> class : Sole owner of an object
shared_ptr -> class : Shared owner of an object
make_unique(args) -> unique_ptr : Builds an object owned by a unique_ptr
make_shared(args) -> shared_ptr : Builds an object owned by a shared_ptr
make_pair(a, b) -> pair : Builds a pair
move(x) : Casts to an rvalue, to move from
forward(x) : Perfect forwarding
swap(a, b) : Exchanges two values
cout -> ostream : Standard output
cerr -> ostream : Standard error
endl : New line and flush
sort(first, last, comp) : Sorts a range
stable_sort(first, last, comp) : Sorts, keeping equal elements in order
find(first, last, value) : First element equal to value
find_if(first, last, pred) : First element satisfying pred
count_if(first, last, pred) : Elements satisfying pred
accumulate(first, last, init) : Sum of a range (<numeric>)
transform(first, last, out, f) : Applies f to a range
for_each(first, last, f) : Calls f on each element
min(a, b) : Smaller value
max(a, b) : Larger value
min_element(first, last) : Smallest element
max_element(first, last) : Largest element
abs(x) : Absolute value
sqrt(x) : Square root
pow(x, y) : Power
exp(x) : Exponential
log(x) : Natural logarithm
hypot(x, y) : √(x² + y²)
atan2(y, x) : Arc tangent
to_string(value) -> string : Number to text
stoi(str) -> int : Text to int
stod(str) -> double : Text to double
getline(stream, str) : Reads a line
ifstream -> class : Input file stream
ofstream -> class : Output file stream
stringstream -> class : String stream
setw(n) : Field width (<iomanip>)
setprecision(n) : Digits printed (<iomanip>)
fixed : Fixed-point notation
runtime_error -> class : Exception with a message
numeric_limits -> class : Properties of arithmetic types
thread -> class : A thread
mutex -> class : A mutex
lock_guard -> class : Locks a mutex for a scope
chrono -> namespace : Clocks and durations
filesystem -> namespace : Paths and directories
@numpy
array(object, dtype=None) -> ndarray : An array from a list
zeros(shape, dtype=float) -> ndarray : Array of zeros
ones(shape, dtype=float) -> ndarray : Array of ones
empty(shape, dtype=float) -> ndarray : Uninitialized array
full(shape, fill_value) -> ndarray : Array filled with a value
arange(start, stop, step) -> ndarray : Evenly spaced values by step
linspace(start, stop, num=50) -> ndarray : num evenly spaced values
logspace(start, stop, num=50) -> ndarray : Values evenly spaced on a log scale
meshgrid(x, y) -> ndarray : Coordinate matrices
mean(a, axis=None) : Mean
median(a, axis=None) : Median
std(a, axis=None, ddof=0) : Standard deviation
var(a, axis=None) : Variance
sum(a, axis=None) : Sum
cumsum(a, axis=None) -> ndarray : Cumulative sum
prod(a, axis=None) : Product
min(a, axis=None) : Minimum
max(a, axis=None) : Maximum
argmin(a, axis=None) : Index of the minimum
argmax(a, axis=None) : Index of the maximum
sqrt(x) -> ndarray : Square root
exp(x) -> ndarray : Exponential
log(x) -> ndarray : Natural logarithm
log10(x) -> ndarray : Base-10 logarithm
sin(x) -> ndarray : Sine
cos(x) -> ndarray : Cosine
arctan2(y, x) -> ndarray : Arc tangent of y/x
abs(x) -> ndarray : Absolute value
clip(a, a_min, a_max) -> ndarray : Limits the values
where(condition, x, y) -> ndarray : Elements chosen by a condition
concatenate(arrays, axis=0) -> ndarray : Joins arrays
stack(arrays, axis=0) -> ndarray : Stacks arrays on a new axis
vstack(arrays) -> ndarray : Stacks rows
hstack(arrays) -> ndarray : Stacks columns
reshape(a, newshape) -> ndarray : New shape
transpose(a) -> ndarray : Swaps axes
dot(a, b) -> ndarray : Dot product
histogram(a, bins=10, range=None, weights=None, density=False) : Counts and bin edges
histogram2d(x, y, bins=10) : 2D counts and edges
percentile(a, q) : q-th percentile
quantile(a, q) : q-th quantile
sort(a) -> ndarray : Sorted copy
argsort(a) -> ndarray : Indices that sort
unique(a) -> ndarray : Sorted unique values
isnan(x) -> ndarray : Element-wise NaN test
isfinite(x) -> ndarray : Element-wise finiteness
polyfit(x, y, deg) -> ndarray : Least-squares polynomial fit
polyval(p, x) -> ndarray : Evaluates a polynomial
interp(x, xp, fp) -> ndarray : Linear interpolation
diff(a, n=1) -> ndarray : Discrete difference
gradient(f) -> ndarray : Numerical gradient
trapz(y, x=None) : Trapezoidal integral
cov(m) -> ndarray : Covariance matrix
corrcoef(x) -> ndarray : Correlation coefficients
digitize(x, bins) -> ndarray : Bin index of each value
bincount(x, weights=None) -> ndarray : Occurrences of each integer
loadtxt(fname, delimiter=None) -> ndarray : Reads a text table
savetxt(fname, X) : Writes a text table
genfromtxt(fname, delimiter=None) -> ndarray : Reads a table with missing values
save(file, arr) : Writes .npy
load(file) -> ndarray : Reads .npy / .npz
pi -> float : π
e -> float : e
inf -> float : Infinity
nan -> float : Not a number
newaxis -> None : Adds an axis when indexing
float64 -> type : 64-bit float
int64 -> type : 64-bit integer
random -> module : Random numbers (np.random.default_rng())
linalg -> module : Linear algebra
fft -> module : Fourier transforms
@ndarray
shape -> tuple : Dimensions
size -> int : Number of elements
ndim -> int : Number of dimensions
dtype -> dtype : Element type
T -> ndarray : Transpose
sum(axis=None) : Sum
mean(axis=None) : Mean
std(axis=None) : Standard deviation
min(axis=None) : Minimum
max(axis=None) : Maximum
reshape(shape) -> ndarray : New shape
flatten() -> ndarray : 1D copy
astype(dtype) -> ndarray : Converted copy
tolist() -> list : Nested lists
copy() -> ndarray : Copy
argmax(axis=None) : Index of the maximum
@pyplot
figure(figsize=None, dpi=None) -> Figure : A new figure
subplots(nrows=1, ncols=1, figsize=None, sharex=False, sharey=False) : A figure and its axes
subplot(nrows, ncols, index) -> Axes : One subplot of a grid
plot(x, y, fmt="", label=None) : Lines and markers
scatter(x, y, s=None, c=None) : Scatter plot
hist(x, bins=10, range=None, histtype="bar", density=False, weights=None, label=None) : Histogram
hist2d(x, y, bins=10) : 2D histogram
bar(x, height, width=0.8) : Bar chart
errorbar(x, y, yerr=None, xerr=None, fmt="", capsize=None) : Points with error bars
step(x, y, where="pre") : Step plot
fill_between(x, y1, y2=0, alpha=None) : Filled band
imshow(X, cmap=None, origin=None, extent=None) : Image of an array
contour(X, Y, Z, levels=None) : Contour lines
contourf(X, Y, Z, levels=None) : Filled contours
pcolormesh(X, Y, C, cmap=None) : Colored grid
colorbar(mappable=None, label=None) : Color scale
xlabel(label) : x axis title
ylabel(label) : y axis title
title(label) : Plot title
suptitle(t) : Figure title
legend(loc="best") : Legend
grid(visible=True) : Grid
xlim(left, right) : x range
ylim(bottom, top) : y range
xscale(value) : "linear" or "log"
yscale(value) : "linear" or "log"
semilogy(x, y) : Log y plot
loglog(x, y) : Log-log plot
axhline(y=0, color=None, linestyle=None) : Horizontal line
axvline(x=0, color=None, linestyle=None) : Vertical line
text(x, y, s) : Text at data coordinates
annotate(text, xy, xytext=None, arrowprops=None) : Annotation with an arrow
tight_layout() : Fits the subplots in the figure
savefig(fname, dpi=None, bbox_inches=None) : Writes the figure (.png, .pdf, .svg)
show() : Displays the figures
close(fig=None) : Closes a figure
gca() -> Axes : Current axes
gcf() -> Figure : Current figure
style -> module : Style sheets (plt.style.use("ggplot"))
rcParams -> dict : Default settings
@Axes
plot(x, y, fmt="", label=None) : Lines and markers
scatter(x, y, s=None, c=None) : Scatter plot
hist(x, bins=10, range=None, histtype="bar", density=False, weights=None) : Histogram
errorbar(x, y, yerr=None, xerr=None, fmt="") : Points with error bars
step(x, y, where="pre") : Step plot
bar(x, height, width=0.8) : Bar chart
fill_between(x, y1, y2=0) : Filled band
imshow(X, cmap=None) : Image of an array
pcolormesh(X, Y, C) : Colored grid
contour(X, Y, Z) : Contour lines
set_xlabel(label) : x axis title
set_ylabel(label) : y axis title
set_title(label) : Title
set_xlim(left, right) : x range
set_ylim(bottom, top) : y range
set_xscale(value) : "linear" or "log"
set_yscale(value) : "linear" or "log"
set_xticks(ticks) : Tick positions
set_yticks(ticks) : Tick positions
set_aspect(aspect) : "equal" or a ratio
tick_params(axis="both", which="major") : Tick appearance
legend(loc="best") : Legend
grid(visible=True) : Grid
axhline(y=0) : Horizontal line
axvline(x=0) : Vertical line
text(x, y, s) : Text
annotate(text, xy) : Annotation
twinx() -> Axes : A second y axis
invert_yaxis() : Flips the y axis
@Figure
add_subplot(nrows, ncols, index) -> Axes : Adds axes
savefig(fname, dpi=None) : Writes the figure
suptitle(t) : Figure title
tight_layout() : Fits the subplots
colorbar(mappable, ax=None) : Color scale
set_size_inches(w, h) : Size
subplots_adjust(left=None, bottom=None, right=None, top=None) : Margins
@uproot
open(path) : Opens a ROOT file without ROOT ("file.root:tree")
concatenate(files, expressions=None) : Arrays from several files
iterate(files, expressions=None, step_size="100 MB") : Arrays in chunks
recreate(path) : Creates a file for writing
update(path) : Opens a file for adding objects
@awkward
Array(data) : Jagged array
flatten(array, axis=1) : Removes a level of nesting
num(array, axis=1) : Length of each list
count(array, axis=None) : Non-missing values
sum(array, axis=None) : Sum
mean(array, axis=None) : Mean
zip(arrays) : Records from arrays
unzip(array) : Arrays from records
to_numpy(array) : Converts a regular array
to_list(array) : Python lists
from_iter(iterable) : Array from Python objects
concatenate(arrays, axis=0) : Joins arrays
where(condition, x, y) : Elements chosen by a condition
combinations(array, n, axis=1) : Pairs (n=2) of each list
cartesian(arrays, axis=1) : Cartesian product per list
argsort(array, axis=-1, ascending=True) : Indices that sort
sort(array, axis=-1, ascending=True) : Sorted
firsts(array, axis=1) : First element of each list
fill_none(array, value) : Replaces missing values
is_none(array) : Missing values
pad_none(array, target, axis=1) : Pads lists to a length
@math
sqrt(x) : Square root
exp(x) : Exponential
log(x, base=e) : Logarithm
log10(x) : Base-10 logarithm
sin(x) : Sine
cos(x) : Cosine
tan(x) : Tangent
atan2(y, x) : Arc tangent of y/x
hypot(x, y) : √(x² + y²)
floor(x) : Floor
ceil(x) : Ceiling
fabs(x) : Absolute value
isclose(a, b, rel_tol=1e-09) : Approximate equality
pi -> float : π
e -> float : e
inf -> float : Infinity
@os
path -> module : Path manipulation
listdir(path=".") : Entries of a directory
makedirs(name, exist_ok=False) : Creates directories
remove(path) : Deletes a file
getcwd() : Working directory
environ -> dict : Environment variables
getenv(key, default=None) : An environment variable
@os.path
join(a, b) : Joins path parts
exists(path) : Whether the path exists
isfile(path) : Whether it is a file
isdir(path) : Whether it is a directory
basename(path) : Last part
dirname(path) : Parent
splitext(path) : (root, extension)
abspath(path) : Absolute path
@sys
argv -> list : Command line arguments
exit(code=0) : Leaves the program
path -> list : Module search path
stdout -> file : Standard output
stderr -> file : Standard error
version -> str : Python version
@scipy.optimize
curve_fit(f, xdata, ydata, p0=None, sigma=None) : Least-squares fit: (popt, pcov)
minimize(fun, x0, method=None) : Minimizes a function
root_scalar(f, bracket=None) : Root of a function
@pandas
DataFrame(data=None, columns=None) : Table
Series(data=None) : Column
read_csv(path, sep=",") : Reads a CSV table
read_parquet(path) : Reads a Parquet table
concat(objs, axis=0) : Joins tables
merge(left, right, on=None) : Database-style join
""";

    static {
        String owner = null;
        for (String raw : DATA.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("@")) {
                String[] parts = line.substring(1).trim().split("\\s+");
                owner = parts[0];
                MEMBERS.computeIfAbsent(owner, k -> new ArrayList<>());
                List<String> parents = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    parents.add(parts[i]);
                }
                PARENTS.put(owner, parents);
                continue;
            }
            if (owner == null) {
                continue;
            }
            String doc = "";
            int colon = findDocColon(line);
            if (colon >= 0) {
                doc = line.substring(colon + 1).trim();
                line = line.substring(0, colon).trim();
            }
            String returns = "";
            int arrow = line.lastIndexOf("->");
            if (arrow >= 0) {
                returns = line.substring(arrow + 2).trim();
                line = line.substring(0, arrow).trim();
            }
            String name = line;
            String params = "";
            int paren = line.indexOf('(');
            if (paren >= 0) {
                name = line.substring(0, paren).trim();
                params = line.substring(paren).trim();
            }
            MEMBERS.get(owner).add(new Member(owner, name, params, returns, doc));
        }
    }

    /** The colon that starts the description, past any "::" and default values. */
    private static int findDocColon(String line) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ':' && i > 0 && line.charAt(i - 1) == ' '
                       && i + 1 < line.length() && line.charAt(i + 1) == ' ') {
                return i;
            }
        }
        return -1;
    }

    public static boolean knows(String type) {
        return type != null && MEMBERS.containsKey(normalize(type));
    }

    /** Members of a class with its bases', the class's own first. */
    public static List<Member> members(String type) {
        if (type == null) {
            return List.of();
        }
        Map<String, Member> out = new LinkedHashMap<>();
        collect(normalize(type), out, 0);
        return new ArrayList<>(out.values());
    }

    private static void collect(String type, Map<String, Member> out, int depth) {
        if (depth > 8) {
            return;
        }
        List<Member> own = MEMBERS.get(type);
        if (own != null) {
            for (Member m : own) {
                out.putIfAbsent(m.name, m);
            }
        }
        for (String parent : PARENTS.getOrDefault(type, List.of())) {
            collect(parent, out, depth + 1);
        }
    }

    public static Member member(String type, String name) {
        for (Member m : members(type)) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** The knowledge-base name of a Python module, or null. */
    public static String moduleKey(String module) {
        return module == null ? null : MODULES.get(module);
    }

    /** Every class and module described, for hover lookups by name. */
    public static java.util.Set<String> owners() {
        return Collections.unmodifiableSet(MEMBERS.keySet());
    }

    /** "std::vector<TH1F*>" and "TH1F*" both read as their class. */
    static String normalize(String type) {
        String t = type.trim();
        t = t.replace("const ", "").replace("*", "").replace("&", "").trim();
        if (t.startsWith("ROOT::") && !t.equals("ROOT::")) {
            String inner = t.substring(6);
            if (MEMBERS.containsKey(inner)) {
                return inner;
            }
        }
        if (t.startsWith("std::unique_ptr<") || t.startsWith("std::shared_ptr<")
                || t.startsWith("unique_ptr<") || t.startsWith("shared_ptr<")
                || t.startsWith("TTreeReaderValue<") || t.startsWith("ROOT::RDF::RResultPtr<")) {
            int lt = t.indexOf('<');
            int gt = t.lastIndexOf('>');
            if (lt >= 0 && gt > lt) {
                return normalize(t.substring(lt + 1, gt));
            }
        }
        if (t.equals("ROOT::RDataFrame") || t.startsWith("ROOT::RDF::RInterface")) {
            return "RDataFrame";
        }
        return t;
    }
}
