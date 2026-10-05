package com.sphere.components.rootview;

/**
 * ROOT's context menus, as ROOT declares them: every member function of a
 * class whose declaration carries the comment *MENU* or *TOGGLE* in ROOT's
 * headers, with its parameters and their defaults, and the base classes of
 * ROOT's classes, so that an object's menu holds its class's functions and
 * those of every class it derives from, as TContextMenu builds it.
 *
 * Generated from the headers of ROOT (root-master) by Sphere's build tools;
 * do not edit by hand. One row per function:
 *
 *   Class|M or T (menu or toggle)|Function|getter of a toggle|parameters
 *
 * and one row per class: Class|Base|Base...
 */
final class RootMenuTable {

    private RootMenuTable() {
    }

    /** The functions, in the order of the headers. */
    static String menus() {
        return MENUS_0;
    }

    /** The base classes. */
    static String bases() {
        return BASES_0;
    }

    private static final String MENUS_0 = """
TAttAxis|M|SetNdivisions||Int_t n=510, Bool_t optim=kTRUE
TAttAxis|M|SetAxisColor||Color_t color=1, Float_t alpha=1.
TAttAxis|M|SetLabelColor||Color_t color=1, Float_t alpha=1.
TAttAxis|M|SetLabelFont||Style_t font=62
TAttAxis|M|SetLabelOffset||Float_t offset=0.005
TAttAxis|M|SetLabelSize||Float_t size=0.04
TAttAxis|M|SetMaxDigits||Int_t maxDigits = 5
TAttAxis|M|SetTickLength||Float_t length=0.03
TAttAxis|M|SetTitleOffset||Float_t offset=1
TAttAxis|M|SetTitleSize||Float_t size=0.04
TAttAxis|M|SetTitleColor||Color_t color=1
TAttAxis|M|SetTitleFont||Style_t font=62
TAttFill|M|SetFillAttributes||
TAttLine|M|SetLineAttributes||
TAttMarker|M|SetMarkerAttributes||
TAttText|M|SetTextAttributes||
TFolder|M|ls||Option_t *option=""
TFolder|M|SaveAs||const char *filename="",Option_t *option=""
TMacro|M|Load||
TMacro|M|Exec||const char *params = nullptr, Int_t *error = nullptr
TMacro|M|Print||Option_t *option=""
TMacro|M|SaveSource||const char *filename
TMacro|M|SetParams||const char *params = nullptr
TNamed|M|SetName||const char *name
TNamed|M|SetTitle||const char *title=""
TObject|M|Delete||Option_t *option = ""
TObject|M|DrawClass||
TObject|M|DrawClone||Option_t *option = ""
TObject|M|Dump||
TObject|M|Inspect||
TObject|M|SaveAs||const char *filename = "", Option_t *option = ""
TObject|M|SetDrawOption||Option_t *option = ""
TSystemFile|M|Rename||const char *name
TSystemFile|M|Delete||
TSystemFile|M|Copy||const char *to
TSystemFile|M|Move||const char *to
TSystemFile|M|Edit||
TTask|M|Abort||
TTask|M|Continue||
TTask|M|ExecuteTask||Option_t *option="0"
TTask|M|ls||Option_t *option="*"
TTask|T|SetActive||Bool_t active=kTRUE
TTask|T|SetBreakin||Int_t breakin=1
TTask|T|SetBreakout||Int_t breakout=1
TGeoManager|M|cd||const char *path = ""
TGeoManager|M|Edit||Option_t *option = ""
TGeoManager|M|ClearAttributes||
TGeoManager|M|DefaultAngles||
TGeoManager|M|DefaultColors||const TGeoColorScheme *cs = nullptr
TGeoManager|M|OptimizeVoxels||const char *filename = "tgeovox.C"
TGeoManager|M|SetClipping||Bool_t flag = kTRUE
TGeoManager|M|SetExplodedView||Int_t iopt = 0
TGeoManager|M|SetNsegments||Int_t nseg
TGeoManager|M|SetBombFactors||Double_t bombx = 1.3, Double_t bomby = 1.3, Double_t bombz = 1.3, Double_t bombr = 1.3
TGeoManager|M|SetVisDensity||Double_t dens = 0.01
TGeoManager|M|SetVisLevel||Int_t level = 3
TGeoManager|T|ViewLeaves|IsVisLeaves|Bool_t flag = kTRUE
TGeoManager|M|SaveAttributes||const char *filename = "tgeoatt.C"
TGeoManager|M|RestoreMasterVolume||
TGeoManager|M|SetMaxVisNodes||Int_t maxnodes = 10000
TGeoManager|M|AnimateTracks||Double_t tmin = 0, Double_t tmax = 5E-8, Int_t nframes = 200, Option_t *option = "/*"
TGeoManager|M|CheckBoundaryErrors||Int_t ntracks = 1000000, Double_t radius = -1.
TGeoManager|M|CheckOverlaps||Double_t ovlp = 0.1, Option_t *option = ""
TGeoManager|M|DrawCurrentPoint||Int_t color = 2
TGeoManager|M|DrawTracks||Option_t *option = ""
TGeoManager|M|PrintOverlaps||
TGeoManager|M|Test||Int_t npoints = 1000000, Option_t *option = ""
TGeoManager|M|TestOverlaps||const char *path = ""
TGeoManager|M|Weight||Double_t precision = 0.01, Option_t *option = "va"
TGeoManager|M|SetRTmode||Int_t mode
TGeoMatrix|M|Print||Option_t *option = ""
TGeoNode|M|CheckOverlaps||Double_t ovlp = 0.1, Option_t *option = ""
TGeoNode|M|CheckOverlapsBySampling||Double_t ovlp = 0.1, Int_t npoints = 1000000
TGeoNode|M|InspectNode||
TGeoNode|M|IsOnScreen||
TGeoNode|M|SetCurrentPoint||Double_t x, Double_t y, Double_t z
TGeoNode|M|SetVisibility||Bool_t vis = kTRUE
TGeoNode|M|SetInvisible||
TGeoNode|M|SetAllInvisible||
TGeoNode|M|PrintCandidates||
TGeoNode|M|PrintOverlaps||
TGeoNode|M|VisibleDaughters||Bool_t vis = kTRUE
TGeoRegion|M|Print||Option_t *option = ""
TGeoShape|M|Draw||Option_t *option = ""
TGeoVolume|M|CheckOverlaps||Double_t ovlp = 0.1, Option_t *option = ""
TGeoVolume|M|CheckOverlapsBySampling||Double_t ovlp = 0.1, Int_t npoints = 1000000
TGeoVolume|M|CheckShape||Int_t testNo, Int_t nsamples = 10000, Option_t *option = ""
TGeoVolume|M|Draw||Option_t *option = ""
TGeoVolume|M|DrawOnly||Option_t *option = ""
TGeoVolume|M|Print||Option_t *option = ""
TGeoVolume|M|PrintVoxels||
TGeoVolume|M|GrabFocus||
TGeoVolume|M|InspectMaterial||
TGeoVolume|M|InspectShape||
TGeoVolume|M|OptimizeVoxels||
TGeoVolume|M|RandomPoints||Int_t npoints = 1000000, Option_t *option = ""
TGeoVolume|T|Raytrace|IsRaytracing|Bool_t flag = kTRUE
TGeoVolume|M|ResetTransparency||Char_t transparency = -1
TGeoVolume|M|SaveAs||const char *filename = "", Option_t *option = ""
TGeoVolume|T|SetAsTopVolume|IsTopVolume|
TGeoVolume|M|SetTransparency||Char_t transparency = 0
TGeoVolume|T|SetVisibility|IsVisible|Bool_t vis = kTRUE
TGeoVolume|T|SetVisContainers|IsVisContainers|Bool_t flag = kTRUE
TGeoVolume|T|SetVisLeaves|IsVisLeaves|Bool_t flag = kTRUE
TGeoVolume|T|SetVisOnly|IsVisOnly|Bool_t flag = kTRUE
TGeoVolume|T|VisibleDaughters|IsVisibleDaughters|Bool_t vis = kTRUE
TGeoVolume|T|InvisibleAll|IsAllInvisible|Bool_t flag = kTRUE
TGeoVolume|M|Weight||Double_t precision = 0.01, Option_t *option = "va"
TGeoOverlap|M|Draw||Option_t *option = ""
TGeoOverlap|M|Print||Option_t *option = ""
TGeoOverlap|M|SampleOverlap||Int_t npoints = 1000000
TGeoOverlap|M|Validate||
TGeoTrack|M|AnimateTrack||Double_t tmin = 0, Double_t tmax = 5E-8, Double_t nframes = 200, Option_t *option = "/*"
TGeoTrack|M|Draw||Option_t *option = ""
TGeoTrack|M|Print||Option_t *option = ""
TGeoVGShape|M|Draw||Option_t *option = ""
TASImage|T|SetEditable||Bool_t on = kTRUE
TASImage|M|SetTitle||const char *title = ""
TASImage|M|Zoom||UInt_t offX, UInt_t offY, UInt_t width, UInt_t height
TASImage|M|UnZoom||
TASImage|M|Flip||Int_t flip = 180
TASImage|M|Mirror||Bool_t vert = kTRUE
TASImage|M|Scale||UInt_t width, UInt_t height
TASImage|M|Tile||UInt_t width, UInt_t height
TASImage|M|Crop||Int_t x = 0, Int_t y = 0, UInt_t width = 0, UInt_t height = 0
TASImage|M|Blur||Double_t hr = 3, Double_t vr = 3
TASImage|T|Gray|IsGray|Bool_t on = kTRUE
TASImage|M|StartPaletteEditor||
TASImage|M|WriteImage||const char *file, EImageFileTypes type = TImage::kUnknown
TASImage|T|SetPaletteEnabled||Bool_t on = kTRUE
TAnnotation|M|SetZ||double z
TButton|M|SetMethod||const char *method
TCanvas|M|DrawClone||Option_t *option=""
TCanvas|M|DrawClonePad||
TCanvas|M|UseCurrentStyle||
TCanvas|T|SetFixedAspectRatio||Bool_t fixed = kTRUE
TCanvas|T|SetGrayscale|IsGrayscale|Bool_t set = kTRUE
TCanvas|M|SetCanvasSize||UInt_t ww, UInt_t wh
TCanvas|M|SetRealAspectRatio||const Int_t axis = 1
TPad|M|BuildLegend||Double_t x1=0.3, Double_t y1=0.21, Double_t x2=0.3, Double_t y2=0.21, const char *title="", Option_t *option = ""
TPad|M|cd||Int_t subpadnumber=0
TPad|M|Divide||Int_t nx=1, Int_t ny=1, Float_t xmargin=0.01, Float_t ymargin=0.01, Int_t color=0
TPad|M|UseCurrentStyle||
TPad|M|Pop||
TPad|M|Range||Double_t x1, Double_t y1, Double_t x2, Double_t y2
TPad|M|SaveAs||const char *filename="",Option_t *option=""
TPad|M|SetBorderMode||Short_t bordermode
TPad|M|SetBorderSize||Short_t bordersize
TPad|T|SetCrosshair||Int_t crhair=1
TPad|T|SetEditable||Bool_t mode=kTRUE
TPad|T|SetFixedAspectRatio||Bool_t fixed = kTRUE
TPad|T|SetGridx||Int_t value = 1
TPad|T|SetGridy||Int_t value = 1
TPad|T|SetLogx||Int_t value = 1
TPad|T|SetLogy||Int_t value = 1
TPad|T|SetLogz||Int_t value = 1
TPad|M|SetName||const char *name
TPad|T|SetTickx||Int_t value = 1
TPad|T|SetTicky||Int_t value = 1
TPaveClass|M|DrawClasses||const char *classes="this"
TPaveClass|M|SaveAs||const char *filename="",Option_t *option=""
TPaveClass|M|SetClasses||const char *classes="this", Option_t *option="ID"
TPaveClass|M|ShowClassesUsedBy||const char *classes="this"
TPaveClass|M|ShowClassesUsing||const char *classes="this"
TPaveClass|M|ShowLinks||Option_t *option="HMR"
TSlider|M|SetMethod||const char *method
TArrow|M|SetAngle||Float_t angle=60
TArrow|M|SetArrowSize||Float_t arrowsize=0.05
TAttImage|T|SetConstRatio||Bool_t constRatio = kTRUE
TAttImage|M|StartPaletteEditor||
TCurlyArc|M|SetCenter||Double_t x1, Double_t y1
TCurlyArc|M|SetRadius||Double_t radius
TCurlyArc|M|SetPhimin||Double_t phimin
TCurlyArc|M|SetPhimax||Double_t phimax
TCurlyLine|M|SetCurly||
TCurlyLine|M|SetWavy||
TCurlyLine|M|SetWaveLength||Double_t WaveLength
TCurlyLine|M|SetAmplitude||Double_t x
TCutG|M|SetVarX||const char *varx
TCutG|M|SetVarY||const char *vary
TEllipse|T|SetNoEdges|GetNoEdges|Bool_t noEdges=kTRUE
TEllipse|M|SetPhimin||Double_t phi=0
TEllipse|M|SetPhimax||Double_t phi=360
TEllipse|M|SetR1||Double_t r1
TEllipse|M|SetR2||Double_t r2
TEllipse|M|SetTheta||Double_t theta=0
TEllipse|M|SetX1||Double_t x1
TEllipse|M|SetY1||Double_t y1
TFrame|M|UseCurrentStyle||
TGaxis|M|SetLabelColor||Int_t labelcolor
TGaxis|M|SetLabelFont||Int_t labelfont
TGaxis|M|SetLabelOffset||Float_t labeloffset
TGaxis|M|SetLabelSize||Float_t labelsize
TGaxis|M|SetName||const char *name
TGaxis|M|SetNdivisions||Int_t ndiv
TGaxis|M|SetMoreLogLabels||Bool_t more=kTRUE
TGaxis|M|SetNoExponent||Bool_t noExponent=kTRUE
TGaxis|M|SetDecimals||Bool_t dot=kTRUE
TGaxis|M|SetTickSize||Float_t ticksize
TGaxis|M|SetTitle||const char *title=""
TGaxis|M|SetTitleOffset||Float_t titleoffset=1
TGaxis|M|SetTitleSize||Float_t titlesize
TGaxis|M|SetTitleFont||Int_t titlefont
TGaxis|M|SetTitleColor||Int_t titlecolor
TGraphPolar|M|SetMaxRadial||Double_t maximum = 1
TGraphPolar|M|SetMinRadial||Double_t minimum = 0
TGraphPolar|M|SetMaxPolar||Double_t maximum = 6.28318530717958623
TGraphPolar|M|SetMinPolar||Double_t minimum = 0
TGraphPolargram|M|SetAxisAngle||Double_t angle = 0
TGraphPolargram|M|SetNdivPolar||Int_t Ndiv = 508
TGraphPolargram|M|SetNdivRadial||Int_t Ndiv = 508
TGraphPolargram|M|SetPolarLabelSize||Double_t angularsize = 0.04
TGraphPolargram|M|SetPolarLabelColor||Color_t tcolorangular = 1
TGraphPolargram|M|SetPolarLabelFont||Font_t tfontangular = 62
TGraphPolargram|M|SetPolarOffset||Double_t PolarOffset=0.04
TGraphPolargram|M|SetRadialOffset||Double_t RadialOffset=0.025
TGraphPolargram|M|SetRadialLabelSize||Double_t radialsize = 0.035
TGraphPolargram|M|SetRadialLabelColor||Color_t tcolorradial = 1
TGraphPolargram|M|SetRadialLabelFont||Font_t tfontradial = 62
TGraphPolargram|M|SetRangePolar||Double_t tmin, Double_t tmax
TGraphPolargram|M|SetRangeRadial||Double_t rmin, Double_t rmax
TGraphPolargram|M|SetTickpolarSize||Double_t tickpolarsize = 0.02
TGraphPolargram|M|SetToDegree||
TGraphPolargram|M|SetToGrad||
TGraphPolargram|M|SetToRadian||
TLegend|M|Clear||Option_t* option = ""
TLegend|M|DeleteEntry||
TLegend|M|SetEntryLabel||const char* label
TLegend|M|SetEntryOption||Option_t* option
TLegend|M|SetHeader||const char *header = "", Option_t *option = ""
TLegend|M|SetMargin||Float_t margin
TLegend|M|SetNColumns||Int_t nColumns
TLegendEntry|M|SetLabel||const char *label = ""
TLegendEntry|M|SetObject||const char *objectName
TLegendEntry|M|SetOption||Option_t *option="lpf"
TLine|T|SetHorizontal|IsHorizontal|Bool_t set = kTRUE
TLine|T|SetVertical|IsVertical|Bool_t set = kTRUE
TMarker|M|SetX||Double_t x
TMarker|M|SetY||Double_t y
TPave|M|SetBorderSize||Int_t bordersize=4
TPave|M|SetCornerRadius||Double_t rad = 0.2
TPave|M|SetName||const char *name=""
TPave|M|SetShadowColor||Int_t color
TPaveLabel|M|SetLabel||const char *label
TPaveStats|M|SaveStyle||
TPaveStats|M|SetFitFormat||const char *format="5.4g"
TPaveStats|M|SetStatFormat||const char *format="6.4g"
TPaveStats|M|SetOptFit||Int_t fit=1
TPaveStats|M|SetOptStat||Int_t stat=1
TPaveStats|M|SetOption||Option_t *option="br"
TPavesText|M|SetNpaves||Int_t npaves=5
TPaveText|M|Clear||Option_t *option=""
TPaveText|M|DeleteText||
TPaveText|M|EditText||
TPaveText|M|InsertLine||
TPaveText|M|InsertText||const char *label
TPaveText|M|ReadFile||const char *filename, Option_t *option="", Int_t nlines=50, Int_t fromline=0
TPaveText|M|SetAllWith||const char *text, Option_t *option, Double_t value
TPaveText|M|SetLabel||const char *label
TPaveText|M|SetMargin||Float_t margin=0.05
TPie|M|Draw||Option_t *option="l"
TPie|M|SetAngle3D||Float_t val = 30.
TPie|M|SetFractionFormat||const char*
TPie|M|SetHeight||Double_t val=.08
TPie|M|SetLabelFormat||const char *
TPie|M|SetLabelsOffset||Float_t
TPie|M|SetPercentFormat||const char *
TPie|M|SetRadius||Double_t
TPie|M|SetValueFormat||const char *
TPie|M|SetX||Double_t
TPie|M|SetY||Double_t
TPieSlice|M|SetRadiusOffset||Double_t
TPieSlice|M|SetValue||Double_t
TPolyLine|M|SetNextPoint||Double_t x, Double_t y
TPolyLine|M|SetPoint||Int_t point, Double_t x, Double_t y
TText|M|SetText||Double_t x, Double_t y, const char *text
TText|M|SetX||Double_t x
TText|M|SetY||Double_t y
TWbox|M|SetBorderMode||Short_t bordermode
TWbox|M|SetBorderSize||Short_t bordersize
TAxis3D|M|SetAxisColor||Color_t color=1, Option_t *axis="*"
TAxis3D|M|SetLabelColor||Color_t color=1, Option_t *axis="*"
TAxis3D|M|SetLabelFont||Style_t font=62, Option_t *axis="*"
TAxis3D|M|SetLabelOffset||Float_t offset=0.005, Option_t *axis="*"
TAxis3D|M|SetLabelSize||Float_t size=0.02, Option_t *axis="*"
TAxis3D|M|SetNdivisions||Int_t n=510, Option_t *axis="*"
TAxis3D|M|SetTickLength||Float_t length=0.02, Option_t *axis="*"
TAxis3D|M|SetTitleOffset||Float_t offset=1, Option_t *axis="*"
TAxis3D|M|SetXTitle||const char *title
TAxis3D|M|SetYTitle||const char *title
TAxis3D|M|SetZTitle||const char *title
TPolyLine3D|M|SetNextPoint||Double_t x, Double_t y, Double_t z
TPolyLine3D|M|SetPoint||Int_t point, Double_t x, Double_t y, Double_t z
TPolyMarker3D|M|SetName||const char *name
TPolyMarker3D|M|SetPoint||Int_t n, Double_t x, Double_t y, Double_t z
TPolyMarker3D|M|SetNextPoint||Double_t x, Double_t y, Double_t z
TView3D|M|SetParallel||
TView3D|M|SetPerspective||
TView3D|M|Centered||
TView3D|M|Front||
TView3D|M|ZoomIn||
TView3D|M|ZoomOut||
TView3D|M|Side||
TView3D|M|Top||
TView3D|M|ShowAxis||
TView3D|M|ZoomMove||
TView3D|M|Zoom||
TView3D|M|UnZoom||
TGLPlotPainter|M|SetSliceWidth||Int_t width = 1
TNode|M|cd||const char *path=nullptr
TNode|M|Draw||Option_t *option=""
TNode|M|ls||Option_t *option="2"
TNode|M|SetVisibility||Int_t vis=1
TShape|M|SetVisibility||Int_t vis
THbookFile|M|Convert2root||const char *rootname="", Int_t lrecl=0, Option_t *option=""
TAxis|M|LabelsOption||Option_t *option="h"
TAxis|T|RotateTitle|GetRotateTitle|Bool_t rotate=kTRUE
TAxis|T|SetDecimals|GetDecimals|Bool_t dot = kTRUE
TAxis|M|SetLimits||Double_t xmin, Double_t xmax
TAxis|T|SetMoreLogLabels|GetMoreLogLabels|Bool_t more=kTRUE
TAxis|T|SetNoExponent|GetNoExponent|Bool_t noExponent=kTRUE
TAxis|M|SetRange||Int_t first=0, Int_t last=0
TAxis|M|SetRangeUser||Double_t ufirst, Double_t ulast
TAxis|M|SetTicks||Option_t *option="+"
TAxis|T|SetTimeDisplay||Int_t value
TAxis|M|SetTimeFormat||const char *format=""
TAxis|M|UnZoom||
TAxis|M|ZoomOut||Double_t factor=0, Double_t offset=0
TF1|M|DrawDerivative||Option_t *option = "al"
TF1|M|DrawIntegral||Option_t *option = "al"
TF1|M|SetMaximum||Double_t maximum = -1111
TF1|M|SetMinimum||Double_t minimum = -1111
TF1|M|SetNpx||Int_t npx = 100
TF1|M|SetRange||Double_t xmin, Double_t xmax
TF1|M|SetTitle||const char *title = ""
TF12|M|SetXY||Double_t xy
TF2|M|SetNpy||Int_t npy=100
TF2|M|SetRange||Double_t xmin, Double_t ymin, Double_t xmax, Double_t ymax
TF3|M|SetClippingBoxOff||
TF3|M|SetClippingBoxOn||Double_t xclip=0, Double_t yclip=0, Double_t zclip=0
TF3|M|SetRange||Double_t xmin, Double_t ymin, Double_t zmin, Double_t xmax, Double_t ymax, Double_t zmax
TGraph|M|DrawPanel||
TGraph|M|Fit||const char *formula ,Option_t *option="" ,Option_t *goption="", Axis_t xmin=0, Axis_t xmax=0
TGraph|M|FitPanel||
TGraph|M|InsertPoint||
TGraph|M|RemovePoint||
TGraph|M|SaveAs||const char *filename = "graph", Option_t *option = ""
TGraph|M|Scale||Double_t c1=1., Option_t *option="y"
TGraph|T|SetEditable|GetEditable|Bool_t editable=kTRUE
TGraph|T|SetHighlight|IsHighlight|Bool_t set = kTRUE
TGraph|M|SetMaximum||Double_t maximum=-1111
TGraph|M|SetMinimum||Double_t minimum=-1111
TGraph|M|SetName||const char *name=""
TGraph|M|SetStats||Bool_t stats=kTRUE
TGraph|M|SetTitle||const char *title=""
TGraph2D|M|Fit||const char *formula ,Option_t *option="" ,Option_t *goption=""
TGraph2D|M|Fit||TF2 *f2 ,Option_t *option="" ,Option_t *goption=""
TGraph2D|M|FitPanel||
TGraph2D|M|Project||Option_t *option="x"
TGraph2D|M|RemovePoint||Int_t ipoint
TGraph2D|M|Scale||Double_t c1=1., Option_t *option="z"
TGraph2D|M|SetMargin||Double_t m=0.1
TGraph2D|M|SetMarginBinsContent||Double_t z=0.
TGraph2D|M|SetMaximum||Double_t maximum=-1111
TGraph2D|M|SetMinimum||Double_t minimum=-1111
TGraph2D|M|SetMaxIter||Int_t n=100000
TGraph2D|M|SetName||const char *name
TGraph2D|M|SetNpx||Int_t npx=40
TGraph2D|M|SetNpy||Int_t npx=40
TGraph2D|M|SetPoint||Int_t point, Double_t x, Double_t y, Double_t z
TGraph2D|M|SetTitle||const char *title=""
TGraph2DAsymmErrors|M|RemovePoint||Int_t ipoint
TGraph2DAsymmErrors|M|Scale||Double_t c1=1., Option_t *option="z"
TGraph2DErrors|M|RemovePoint||Int_t ipoint
TGraph2DErrors|M|Scale||Double_t c1=1., Option_t *option="z"
TGraphAsymmErrors|M|Scale||Double_t c1=1., Option_t *option="y"
TGraphAsymmErrors|M|SetPointError||Double_t exl, Double_t exh, Double_t eyl, Double_t eyh
TGraphBentErrors|M|Scale||Double_t c1=1., Option_t *option="y"
TGraphErrors|M|Scale||Double_t c1=1., Option_t *option="y"
TGraphMultiErrors|M|Scale||Double_t c1=1., Option_t *option="y"
TH1|M|DrawPanel||
TH1|M|Fit||const char *formula ,Option_t *option="" ,Option_t *goption="", Double_t xmin=0, Double_t xmax=0
TH1|M|FitPanel||
TH1|M|Normalize||Option_t *option=""
TH1|M|Rebin||Int_t ngroup = 2, const char *newname = "", const Double_t *xbins = nullptr
TH1|M|SaveAs||const char *filename = "hist", Option_t *option = ""
TH1|M|Scale||Double_t c1=1, Option_t *option=""
TH1|T|SetHighlight|IsHighlight|Bool_t set = kTRUE
TH1|M|SetMaximum||Double_t maximum = -1111
TH1|M|SetMinimum||Double_t minimum = -1111
TH1|M|SetName||const char *name
TH1|M|SetStats||Bool_t stats=kTRUE
TH1|M|SetTitle||const char *title
TH1|M|ShowBackground||Int_t niter=20, Option_t *option="same"
TH1|M|ShowPeaks||Double_t sigma=2, Option_t *option="", Double_t threshold=0.05
TH1|M|Smooth||Int_t ntimes=1, Option_t *option=""
TH2|M|RebinX||Int_t ngroup=2, const char *newname=""
TH2|M|RebinY||Int_t ngroup=2, const char *newname=""
TH2|M|Rebin2D||Int_t nxgroup=2, Int_t nygroup=2, const char *newname="", const Double_t *xbins=nullptr, const Double_t *ybins=nullptr
TH2|M|ProfileX||const char *name="_pfx", Int_t firstybin=1, Int_t lastybin=-1, Option_t *option=""
TH2|M|ProfileY||const char *name="_pfy", Int_t firstxbin=1, Int_t lastxbin=-1, Option_t *option=""
TH2|M|ProjectionX||const char *name="_px", Int_t firstybin=0, Int_t lastybin=-1, Option_t *option=""
TH2|M|ProjectionY||const char *name="_py", Int_t firstxbin=0, Int_t lastxbin=-1, Option_t *option=""
TH2|M|SetShowProjectionX||Int_t nbins=1
TH2|M|SetShowProjectionY||Int_t nbins=1
TH2|M|SetShowProjectionXY||Int_t nbinsY=1, Int_t nbinsX=1
TH2|M|ShowPeaks||Double_t sigma = 2, Option_t *option = "", Double_t threshold = 0.05
TH2|M|Smooth||Int_t ntimes = 1, Option_t *option = ""
TH3|M|Project3D||Option_t *option="x"
TH3|M|Project3DProfile||Option_t *option="xy"
TH3|M|SetShowProjection||const char *option="xy",Int_t nbins=1
THStack|M|SetMaximum||Double_t maximum=-1111
THStack|M|SetMinimum||Double_t minimum=-1111
TMultiDimFit|M|Clear||Option_t *option=""
TMultiDimFit|M|FindParameterization||Option_t* option=""
TMultiDimFit|M|Fit||Option_t *option=""
TMultiDimFit|M|MakeCode||const char *functionName="MDF", Option_t *option=""
TMultiDimFit|M|MakeHistograms||Option_t* option="A"
TMultiDimFit|M|MakeMethod||const Char_t* className="MDF", Option_t* option=""
TMultiDimFit|M|Print||Option_t *option="ps"
TMultiGraph|M|FitPanel||
TPolyMarker|M|SetNextPoint||Double_t x, Double_t y
TPolyMarker|M|SetPoint||Int_t point, Double_t x, Double_t y
TPrincipal|M|MakeCode||const char *filename ="pca", Option_t *option=""
TPrincipal|M|MakeHistograms||const char *name = "pca", Option_t *option="epsdx"
TPrincipal|M|MakeMethods||const char *classname = "PCA", Option_t *option=""
TPrincipal|M|MakePrincipals||
TPrincipal|M|Print||Option_t *opt="MSE"
TPrincipal|M|Test||Option_t *option=""
TProfile|M|Add||const TH1 *h1, const TH1 *h2, Double_t c1=1, Double_t c2=1
TProfile|M|Divide||const TH1 *h1, const TH1 *h2, Double_t c1=1, Double_t c2=1, Option_t *option=""
TProfile|M|Multiply||const TH1 *h1, const TH1 *h2, Double_t c1=1, Double_t c2=1, Option_t *option=""
TProfile|M|SetErrorOption||Option_t *option=""
TProfile2D|M|Add||const TH1 *h1, const TH1 *h2, Double_t c1=1, Double_t c2=1
TProfile2D|M|Divide||const TH1 *h1, const TH1 *h2, Double_t c1=1, Double_t c2=1, Option_t *option=""
TProfile2D|M|Multiply||const TH1 *h1, const TH1 *h2, Double_t c1=1, Double_t c2=1, Option_t *option=""
TProfile2D|M|ProfileX||const char *name="_pfx", Int_t firstybin=0, Int_t lastybin=-1, Option_t *option=""
TProfile2D|M|ProfileY||const char *name="_pfy", Int_t firstxbin=0, Int_t lastxbin=-1, Option_t *option=""
TProfile2D|M|SetErrorOption||Option_t *option=""
TProfile3D|M|Project3DProfile||Option_t *option="xy"
TProfile3D|M|SetErrorOption||Option_t *option=""
TPaletteAxis|M|SetNdivisions||Int_t ndiv=10
TPaletteAxis|M|SetLabelColor||Int_t color=1
TPaletteAxis|M|SetLabelFont||Int_t font=42
TPaletteAxis|M|SetLabelOffset||Float_t offset=0.005
TPaletteAxis|M|SetLabelSize||Float_t size=0.035
TPaletteAxis|M|SetMaxDigits||Float_t maxdigits=5
TPaletteAxis|M|SetTickLength||Float_t length=0.03
TPaletteAxis|M|SetTitleOffset||Float_t offset=1
TPaletteAxis|M|SetTitleSize||Float_t size=0.035
TPaletteAxis|M|SetTitleColor||Int_t color=1
TPaletteAxis|M|SetTitleFont||Int_t font=42
TPaletteAxis|M|SetTitle||const char *title=""
TPaletteAxis|M|SetAxisColor||Int_t color=1, Float_t alpha=1
TPaletteAxis|M|SetLineWidth||Width_t width
TPaletteAxis|M|UnZoom||
TFile|M|Close||Option_t *option=""
TFile|M|DrawMap||const char *keys="*",Option_t *option=""
TFile|M|Map||Option_t *opt
TFile|M|Map||
TSQLFile|M|StartLogFile||const char *fname
TSQLFile|M|StopLogFile||
TSQLFile|M|Close||Option_t *option = ""
TSQLFile|M|MakeProject||const char *, const char * = "*", Option_t * = "new"
TXMLFile|M|Close||Option_t *option = ""
TXMLFile|M|MakeProject||const char *, const char * = "*", Option_t * = "new"
TDecompBK|M|Print||Option_t *opt =""
TDecompChol|M|Print||Option_t *opt =""
TDecompLU|M|Print||Option_t *opt =""
TDecompQRH|M|Print||Option_t *opt =""
TDecompSparse|M|Print||Option_t *opt =""
TDecompSVD|M|Print||Option_t *opt =""
TMatrixTBase|M|Draw||Option_t *option=""
TMatrixTBase|M|Print||Option_t *name  =""
TVectorT|M|Draw||Option_t *option = ""
TVectorT|M|Print||Option_t *option = ""
TGenerator|M|SetPtCut||Float_t ptcut=0
TGenerator|M|SetViewRadius||Float_t rbox = 1000
TGenerator|M|ShowNeutrons||Bool_t show=1
TParticleClassPDG|M|Print||Option_t* opt=""
TParticlePDG|M|Print||Option_t* opt = ""
TChain|M|Draw||const char* varexp, const char* selection, Option_t* option = "", Long64_t nentries = kMaxEntries, Long64_t firstentry = 0
TChain|M|Process||const char *filename, Option_t *option="", Long64_t nentries=kMaxEntries, Long64_t firstentry=0
TChain|M|Scan||const char *varexp="", const char *selection="", Option_t *option="", Long64_t nentries=kMaxEntries, Long64_t firstentry=0
TEntryList|T|SetReapplyCut|GetReapplyCut|bool apply = false
TEventList|M|SetName||const char *name
TEventList|T|SetReapplyCut||bool apply = false
TTree|M|Delete||Option_t* option = ""
TTree|M|Draw||const char* varexp, const char* selection, Option_t* option = "", Long64_t nentries = kMaxEntries, Long64_t firstentry = 0
TTree|M|Fit||const char* funcname, const char* varexp, const char* selection = "", Option_t* option = "", Option_t* goption = "", Long64_t nentries = kMaxEntries, Long64_t firstentry = 0
TTree|M|Print||Option_t* option = ""
TTree|M|Process||const char* filename, Option_t* option = "", Long64_t nentries = kMaxEntries, Long64_t firstentry = 0
TTree|M|Scan||const char* varexp = "", const char* selection = "", Option_t* option = "", Long64_t nentries = kMaxEntries, Long64_t firstentry = 0
TTree|M|SetDebug||Int_t level = 1, Long64_t min = 0, Long64_t max = 9999999
TTree|M|SetMaxEntryLoop||Long64_t maxev = kMaxEntries
TTree|M|SetMaxVirtualSize||Long64_t size = 0
TTree|M|SetName||const char* name
TTree|M|SetScanField||Int_t n = 50
TTree|M|StartViewer||
TFileDrawMap|M|AnimateTree||const char *branches=""
TFileDrawMap|M|DrawObject||
TFileDrawMap|M|DumpObject||
TFileDrawMap|M|InspectObject||
TParallelCoord|M|ApplySelectionToTree||
TParallelCoord|M|SaveEntryLists||const char* filename="", bool overwrite=false
TParallelCoord|M|SaveTree||const char* filename="", bool overwrite=false
TParallelCoord|M|SetAxisHistogramBinning||Int_t n=100
TParallelCoord|M|SetAxisHistogramHeight||Double_t h=0.5
TParallelCoord|M|SetAxisHistogramLineWidth||Int_t lw=2
TParallelCoord|T|SetCandleChart|GetCandleChart|bool can
TParallelCoord|T|SetCurveDisplay|GetCurveDisplay|bool curve=true
TParallelCoord|M|SetDotsSpacing||Int_t s=0
TParallelCoord|T|SetGlobalScale|GetGlobalScale|bool gl
TParallelCoord|T|SetGlobalLogScale|GetGlobalLogScale|bool
TParallelCoord|T|SetVertDisplay|GetVertDisplay|bool vert=true
TParallelCoord|M|SetWeightCut||Int_t w=0
TParallelCoord|M|UnzoomAll||
TParallelCoordRange|M|BringOnTop||
TParallelCoordRange|M|Delete||const Option_t* options=""
TParallelCoordRange|M|Print||Option_t *options
TParallelCoordRange|M|SendToBack||
TParallelCoordVar|M|AddRange||
TParallelCoordVar|M|DeleteVariable||
TParallelCoordVar|M|Print||Option_t* option=""
TParallelCoordVar|T|SetBoxPlot|GetBoxPlot|bool box
TParallelCoordVar|T|SetBarHisto|GetBarHisto|bool h
TParallelCoordVar|M|SetHistogramLineWidth||Int_t lw=2
TParallelCoordVar|M|SetHistogramHeight||Double_t h=0
TParallelCoordVar|M|SetHistogramBinning||Int_t n=100
TParallelCoordVar|M|SetCurrentLimits||Double_t min, Double_t max
TParallelCoordVar|T|SetLogScale|GetLogScale|bool log
TParallelCoordVar|M|Unzoom||
TSpider|M|AddVariable||const char* varexp
TSpider|M|DeleteVariable||const char* varexp
TSpider|M|GotoEntry||Long64_t e
TSpider|M|GotoNext||
TSpider|M|GotoPrevious||
TSpider|M|GotoFollowing||
TSpider|M|GotoPreceding||
TSpider|T|SetDisplayAverage||bool disp
TSpider|M|SetNdivRadial||Int_t div
TSpider|M|SetNx||UInt_t nx
TSpider|M|SetNy||UInt_t ny
TSpider|T|SetSegmentDisplay||bool seg
TTreeViewer|M|Delete||Option_t *
TTreeViewer|M|EmptyAll||
TTreeViewer|M|ExecuteCommand||const char* command, bool fast = false
TTreeViewer|M|MakeSelector||const char* selector = nullptr
TTreeViewer|M|NewExpression||
TTreeViewer|M|Process||const char* filename, Option_t *option="", Long64_t nentries=TTree::kMaxEntries, Long64_t firstentry=0
TTreeViewer|M|RemoveLastRecord||
TTreeViewer|M|SaveSource||const char* filename="", Option_t *option=""
TTreeViewer|M|SetRecordName||const char *name
TTreeViewer|M|SetScanFileName||const char *name=""
TTreeViewer|M|SetUserCode||const char *code, bool autoexec=true
TTreeViewer|M|SetTreeName||const char* treeName
TTreeViewer|M|UpdateRecord||const char *name="new name"
""";

    private static final String BASES_0 = """
TASImage|TImage
TAnnotation|TLatex
TArc|TEllipse
TArrayC|TArray
TArrayD|TArray
TArrayF|TArray
TArrayI|TArray
TArrayS|TArray
TArrow|TLine|TAttFill
TAxis|TNamed|TAttAxis
TAxis3D|TNamed
TBox|TObject|TAttLine|TAttFill|TAttBBox2D
TButton|TPad|TAttText
TCanvas|TPad
TChain|TTree
TCrown|TEllipse
TCurlyArc|TCurlyLine
TCurlyLine|TPolyLine|TAttBBox2D
TCutG|TGraph
TDecompBK|TDecompBase
TDecompBase|TObject
TDecompChol|TDecompBase
TDecompLU|TDecompBase
TDecompQRH|TDecompBase
TDecompSVD|TDecompBase
TDecompSparse|TDecompBase
TDirectory|TNamed
TDirectoryFile|TDirectory
TEfficiency|TNamed|TAttLine|TAttFill|TAttMarker
TEllipse|TObject|TAttLine|TAttFill|TAttBBox2D
TEntryList|TNamed
TEventList|TNamed
TF1|TNamed|TAttLine|TAttFill|TAttMarker
TF12|TF1
TF2|TF1
TF3|TF2
TFile|TDirectoryFile
TFileDrawMap|TNamed
TFolder|TNamed
TFrame|TWbox
TGCompositeFrame|TGFrame
TGFrame|TGWindow|TQObject
TGLPlotPainter|TVirtualGLPainter
TGMainFrame|TGCompositeFrame
TGObject|TObject
TGWindow|TGObject
TGaxis|TLine|TAttText
TGenerator|TNamed
TGeoBBox|TGeoShape
TGeoManager|TNamed
TGeoMatrix|TNamed
TGeoNode|TNamed|TGeoAtt
TGeoNodeMatrix|TGeoNode
TGeoOverlap|TNamed|TAttLine|TAttFill|TAtt3D
TGeoRegion|TNamed
TGeoShape|TNamed
TGeoTrack|TVirtualGeoTrack
TGeoVGShape|TGeoBBox
TGeoVolume|TNamed|TGeoAtt|TAttLine|TAttFill|TAtt3D
TGeoVolumeAssembly|TGeoVolume
TGraph|TNamed|TAttLine|TAttFill|TAttMarker
TGraph2D|TNamed|TAttLine|TAttFill|TAttMarker
TGraph2DAsymmErrors|TGraph2D
TGraph2DErrors|TGraph2D
TGraphAsymmErrors|TGraph
TGraphBentErrors|TGraph
TGraphErrors|TGraph
TGraphMultiErrors|TGraph
TGraphPolar|TGraphErrors
TGraphPolargram|TNamed|TAttText|TAttLine
TH1|TNamed|TAttLine|TAttFill|TAttMarker
TH1C|TH1|TArrayC
TH1D|TH1|TArrayD
TH1F|TH1|TArrayF
TH1I|TH1|TArrayI
TH1S|TH1|TArrayS
TH2|TH1
TH2C|TH2|TArrayC
TH2D|TH2|TArrayD
TH2F|TH2|TArrayF
TH2I|TH2|TArrayI
TH2Poly|TH2
TH2S|TH2|TArrayS
TH3|TH1|TAtt3D
TH3C|TH3|TArrayC
TH3D|TH3|TArrayD
TH3F|TH3|TArrayF
TH3I|TH3|TArrayI
TH3S|TH3|TArrayS
THStack|TNamed
THbookFile|TNamed
TImage|TNamed|TAttImage
TKey|TNamed
TLatex|TText|TAttLine
TLegend|TPave|TAttText
TLegendEntry|TObject|TAttText|TAttLine|TAttFill|TAttMarker
TLine|TObject|TAttLine|TAttBBox2D
TMacro|TNamed
TMarker|TObject|TAttMarker|TAttBBox2D
TMathText|TText|TAttFill
TMatrixTBase|TObject
TMultiDimFit|TNamed
TMultiGraph|TNamed
TNamed|TObject
TNode|TNamed|TAttLine|TAttFill|TAtt3D
TNtuple|TTree
TNtupleD|TTree
TPad|TVirtualPad|TAttBBox2D
TPaletteAxis|TPave
TParallelCoord|TNamed
TParallelCoordRange|TNamed|TAttLine
TParallelCoordVar|TNamed|TAttLine|TAttFill
TParticleClassPDG|TNamed
TParticlePDG|TNamed
TPave|TBox
TPaveClass|TPaveLabel
TPaveLabel|TPave|TAttText
TPaveStats|TPaveText|TVirtualPaveStats
TPaveText|TPave|TAttText
TPavesText|TPaveText
TPie|TNamed|TAttText
TPieSlice|TNamed|TAttFill|TAttLine
TPolyLine|TObject|TAttLine|TAttFill
TPolyLine3D|TObject|TAttLine|TAtt3D
TPolyMarker|TObject|TAttMarker
TPolyMarker3D|TObject|TAttMarker|TAtt3D
TPrincipal|TNamed
TProfile|TH1D
TProfile2D|TH2D
TProfile3D|TH3D
TSQLFile|TFile
TShape|TNamed|TAttLine|TAttFill|TAtt3D
TSlider|TPad
TSpider|TObject|TAttFill|TAttLine
TSystemFile|TNamed
TTask|TNamed
TText|TNamed|TAttText|TAttBBox2D
TTree|TNamed|TAttLine|TAttFill|TAttMarker
TTreeViewer|TGMainFrame
TVectorT|TObject
TView|TObject|TAttLine
TView3D|TView
TVirtualGeoTrack|TObject|TGeoAtt|TAttLine|TAttMarker
TVirtualPad|TObject|TAttLine|TAttFill|TAttPad|TQObject
TWbox|TBox
TXMLFile|TFile|TXMLSetup
""";
}
