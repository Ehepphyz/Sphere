package com.sphere.core.python.jupyterlab;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What to do about a cell's error, read from the error, the notebook around the
 * cell and the files around the notebook: not only what failed, but the fix, one
 * click away.
 *
 * <pre>
 *   NameError            another cell defines the name and has not run: run it first;
 *                        a usual alias (np, pd, plt...): import it; a typo of a name the
 *                        notebook or the kernel knows: use that name
 *   ModuleNotFoundError  a module of one's own in a folder beside the notebook: put the
 *                        folder on sys.path; a package Sphere knows: pip install it into the
 *                        kernel's own Python; a typo of a known package: use that name
 *   ImportError,         Python's own "Did you mean", carried out
 *   AttributeError
 *   FileNotFoundError    a file of that name under the notebook's folder: use its path
 *   SyntaxError          "expected ':'": the colon, added
 *   IndentationError     a block left empty, a line indented too far: the indent, set right
 * </pre>
 *
 * Every edit is computed here, on the text the cell ran, as replacements outside its
 * strings and comments: the editor applies them only while the cell still holds that
 * text. Looking for files is I/O: the editor calls this off the event thread.
 */
final class CellDoctor {

    private CellDoctor() {
    }

    // ---- What the doctor prescribes ----------------------------------------

    /** One replacement in the text the doctor read. */
    record Span(int start, int end, String text) {
    }

    sealed interface Fix permits Edit, RunFirst, Install, Copy {
        String label();

        String tip();
    }

    /** Replacements in the cell's text, then the cell runs again. */
    record Edit(String label, String tip, List<Span> spans) implements Fix {
    }

    /** Runs the code cell defining a missing name, with what that cell needs, then the failing cell. */
    record RunFirst(String label, String tip, int definer) implements Fix {
    }

    /** pip install into the kernel's own Python, then the cell runs again. */
    record Install(String label, String tip, String pipName) implements Fix {
    }

    /** Text to the clipboard: what the doctor cannot do in the cell itself. */
    record Copy(String label, String tip, String text) implements Fix {
    }

    /** One sentence, and the fixes, best first. A sentence with no fix still says what the error does not. */
    record Diagnosis(String message, List<Fix> fixes) {
        static final Diagnosis NONE = new Diagnosis(null, List.of());

        boolean isEmpty() {
            return message == null || message.isBlank();
        }
    }

    /**
     * What the notebook knows around the failing cell.
     *
     * @param source the text the cell ran: the error's line numbers count in it
     * @param line   the failing line, 1-based; -1 when the traceback did not say
     * @param self   the cell's place among the code cells
     * @param binds  what each code cell binds at module level
     * @param ran    whether each code cell ran on this kernel
     * @param label  how a code cell is named ("[3]", "cell 4")
     * @param kernel the names the kernel holds
     * @param folder where the kernel runs: the notebook's folder
     */
    record Context(String source, int line, int self, List<Set<String>> binds, List<Boolean> ran,
                   IntFunction<String> label, Set<String> kernel, Path folder) {
    }

    static Diagnosis diagnose(String ename, String evalue, Context ctx) {
        if (ename == null || ctx == null || ctx.source() == null) {
            return Diagnosis.NONE;
        }
        String value = evalue == null ? "" : AnsiText.strip(evalue).strip();
        return switch (ename) {
            case "NameError" -> nameError(value, ctx);
            case "ModuleNotFoundError" -> moduleNotFound(value, ctx);
            case "ImportError" -> importError(value, ctx);
            case "AttributeError" -> attributeError(value, ctx);
            case "FileNotFoundError" -> fileNotFound(value, ctx);
            case "SyntaxError" -> syntaxError(value, ctx);
            case "IndentationError" -> indentationError(value, ctx);
            default -> Diagnosis.NONE;
        };
    }

    // ---- NameError ---------------------------------------------------------

    private static final Pattern NAME = Pattern.compile("name '([A-Za-z_][A-Za-z0-9_]*)' is not defined");
    private static final Pattern MEAN = Pattern.compile("Did you mean:? '([A-Za-z_][A-Za-z0-9_.]*)'");
    private static final Pattern FORGOT = Pattern.compile("Did you forget to import '([A-Za-z_][A-Za-z0-9_.]*)'");

    private static Diagnosis nameError(String value, Context ctx) {
        Matcher m = NAME.matcher(value);
        if (!m.find()) {
            return Diagnosis.NONE;
        }
        String name = m.group(1);
        // Another cell defines it: the nearest above, else the first below.
        int definer = -1;
        for (int i = ctx.self() - 1; i >= 0 && definer < 0; i--) {
            if (binds(ctx, i).contains(name)) {
                definer = i;
            }
        }
        for (int i = ctx.self() + 1; i < ctx.binds().size() && definer < 0; i++) {
            if (binds(ctx, i).contains(name)) {
                definer = i;
            }
        }
        if (definer >= 0) {
            String who = ctx.label().apply(definer);
            boolean ran = definer < ctx.ran().size() && Boolean.TRUE.equals(ctx.ran().get(definer));
            String message = "'" + name + "' is defined by " + who + (definer > ctx.self() ? ", below this cell" : "")
                             + (ran ? " -- but the kernel no longer holds it" : ", which has not run on this kernel");
            return new Diagnosis(message, List.of(new RunFirst("Run " + who + " first",
                "<html>Runs " + escape(who) + " (it defines <b>" + escape(name) + "</b>) and what it needs, then this cell</html>",
                definer)));
        }
        if (binds(ctx, ctx.self()).contains(name)) {
            return new Diagnosis("'" + name + "' is read before this cell assigns it", List.of());
        }
        List<Fix> fixes = new ArrayList<>();
        Matcher forgot = FORGOT.matcher(value);
        boolean pythonSaid = forgot.find();
        String statement = pythonSaid ? "import " + forgot.group(1) : importFor(name, ctx.source());
        if (statement != null) {
            fixes.add(new Edit("Add '" + statement + "'", "Adds the import at the top of this cell, then runs it again",
                               List.of(addLine(ctx.source(), statement))));
        }
        // A usual alias wins over a near name: 'pd' is pandas, not a typo of 'id'.
        Matcher mean = MEAN.matcher(value);
        String typo = statement != null ? null : mean.find() ? mean.group(1) : closest(name, candidates(ctx), 3);
        if (typo == null && statement == null) {
            typo = closest(name, BUILTINS, 1); // Python's names are short: one slip at most
        }
        if (typo != null && (typo.equals(name) || typo.indexOf('.') >= 0)) {
            typo = null;
        }
        if (typo != null) {
            List<Span> at = occurrences(ctx.source(), name, false);
            if (!at.isEmpty()) {
                fixes.add(new Edit("Use '" + typo + "'", "Replaces '" + name + "' with '" + typo + "' in this cell"
                                   + (at.size() > 1 ? " (" + at.size() + " places)" : "") + ", then runs it again",
                                   replaced(at, typo)));
            }
        }
        if (statement != null) {
            return new Diagnosis("'" + name + "' is not defined: " + (pythonSaid ? "the module was never imported"
                                 : "it is the usual name of " + importedName(statement)), fixes);
        }
        if (typo != null) {
            return new Diagnosis("'" + name + "' is not defined: did you mean '" + typo + "'?", fixes);
        }
        return new Diagnosis("No cell of this notebook defines '" + name + "', and the kernel does not hold it", List.of());
    }

    private static Set<String> binds(Context ctx, int i) {
        return i >= 0 && i < ctx.binds().size() && ctx.binds().get(i) != null ? ctx.binds().get(i) : Set.of();
    }

    /** The names a typo may stand for: the notebook's first, then the kernel's (Python's are tried apart). */
    private static Collection<String> candidates(Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> b : ctx.binds()) {
            if (b != null) {
                out.addAll(b);
            }
        }
        if (ctx.kernel() != null) {
            out.addAll(ctx.kernel());
        }
        return out;
    }

    private static final List<String> BUILTINS = List.of(
        "print", "len", "range", "list", "dict", "set", "tuple", "str", "int", "float", "bool", "abs", "min", "max",
        "sum", "sorted", "enumerate", "zip", "map", "filter", "open", "isinstance", "issubclass", "type", "round",
        "any", "all", "input", "format", "repr", "iter", "next", "reversed", "getattr", "setattr", "hasattr",
        "delattr", "super", "object", "divmod", "pow", "hex", "bin", "oct", "chr", "ord", "vars", "dir", "id",
        "hash", "callable", "slice", "property", "staticmethod", "classmethod", "frozenset", "bytes", "bytearray",
        "complex", "eval", "exec", "globals", "locals", "help", "True", "False", "None", "Exception",
        "ValueError", "TypeError", "KeyError", "IndexError", "RuntimeError", "AttributeError", "StopIteration",
        "NotImplementedError", "ZeroDivisionError", "FileNotFoundError", "ImportError", "AssertionError");

    /**
     * The import a usual alias stands for. Where a name is both a module and a class of it
     * (datetime, tqdm), how the cell uses it decides: datetime.datetime needs the module.
     */
    static String importFor(String name, String source) {
        if (name.equals("datetime")) {
            return source.matches("(?s).*\\bdatetime\\.(datetime|date|time|timedelta|timezone)\\b.*")
                   ? "import datetime" : "from datetime import datetime";
        }
        if (name.equals("tqdm")) {
            return source.matches("(?s).*\\btqdm\\.(tqdm|auto|notebook|trange)\\b.*") ? "import tqdm" : "from tqdm import tqdm";
        }
        String s = ALIASES.get(name);
        if (s != null) {
            return s;
        }
        return STDLIB.contains(name) ? "import " + name : null;
    }

    /** "numpy" for "import numpy as np", "collections.Counter" for "from collections import Counter". */
    static String importedName(String statement) {
        Matcher from = Pattern.compile("from ([\\w.]+) import (\\w+)").matcher(statement);
        if (from.find()) {
            return from.group(1) + "." + from.group(2);
        }
        Matcher imp = Pattern.compile("import ([\\w.]+)").matcher(statement);
        return imp.find() ? imp.group(1) : statement;
    }

    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("np", "import numpy as np"),
        Map.entry("pd", "import pandas as pd"),
        Map.entry("plt", "import matplotlib.pyplot as plt"),
        Map.entry("mpl", "import matplotlib as mpl"),
        Map.entry("sns", "import seaborn as sns"),
        Map.entry("sp", "import scipy as sp"),
        Map.entry("stats", "from scipy import stats"),
        Map.entry("optimize", "from scipy import optimize"),
        Map.entry("curve_fit", "from scipy.optimize import curve_fit"),
        Map.entry("minimize", "from scipy.optimize import minimize"),
        Map.entry("quad", "from scipy.integrate import quad"),
        Map.entry("interp1d", "from scipy.interpolate import interp1d"),
        Map.entry("tf", "import tensorflow as tf"),
        Map.entry("torch", "import torch"),
        Map.entry("nn", "import torch.nn as nn"),
        Map.entry("F", "import torch.nn.functional as F"),
        Map.entry("keras", "import keras"),
        Map.entry("jax", "import jax"),
        Map.entry("jnp", "import jax.numpy as jnp"),
        Map.entry("ak", "import awkward as ak"),
        Map.entry("uproot", "import uproot"),
        Map.entry("hist", "import hist"),
        Map.entry("bh", "import boost_histogram as bh"),
        Map.entry("hep", "import mplhep as hep"),
        Map.entry("vector", "import vector"),
        Map.entry("iminuit", "import iminuit"),
        Map.entry("Minuit", "from iminuit import Minuit"),
        Map.entry("pyhf", "import pyhf"),
        Map.entry("ROOT", "import ROOT"),
        Map.entry("fastjet", "import fastjet"),
        Map.entry("px", "import plotly.express as px"),
        Map.entry("go", "import plotly.graph_objects as go"),
        Map.entry("pl", "import polars as pl"),
        Map.entry("xr", "import xarray as xr"),
        Map.entry("dd", "import dask.dataframe as dd"),
        Map.entry("nx", "import networkx as nx"),
        Map.entry("sm", "import statsmodels.api as sm"),
        Map.entry("smf", "import statsmodels.formula.api as smf"),
        Map.entry("cv2", "import cv2"),
        Map.entry("Image", "from PIL import Image"),
        Map.entry("Path", "from pathlib import Path"),
        Map.entry("defaultdict", "from collections import defaultdict"),
        Map.entry("Counter", "from collections import Counter"),
        Map.entry("OrderedDict", "from collections import OrderedDict"),
        Map.entry("namedtuple", "from collections import namedtuple"),
        Map.entry("deque", "from collections import deque"),
        Map.entry("dataclass", "from dataclasses import dataclass"),
        Map.entry("partial", "from functools import partial"),
        Map.entry("reduce", "from functools import reduce"),
        Map.entry("lru_cache", "from functools import lru_cache"),
        Map.entry("product", "from itertools import product"),
        Map.entry("combinations", "from itertools import combinations"),
        Map.entry("permutations", "from itertools import permutations"),
        Map.entry("display", "from IPython.display import display"),
        Map.entry("Optional", "from typing import Optional"),
        Map.entry("Any", "from typing import Any"),
        Map.entry("Callable", "from typing import Callable"));

    private static final Set<String> STDLIB = Set.of(
        "os", "sys", "re", "json", "math", "time", "random", "glob", "shutil", "pickle", "itertools", "functools",
        "collections", "subprocess", "pathlib", "csv", "logging", "argparse", "copy", "string", "struct",
        "threading", "typing", "dataclasses", "statistics", "decimal", "fractions", "operator", "heapq", "bisect",
        "textwrap", "warnings", "io", "gzip", "zipfile", "tarfile", "tempfile", "hashlib", "base64", "uuid",
        "socket", "urllib", "enum", "contextlib", "inspect", "traceback", "platform", "signal", "queue", "array",
        "timeit", "pprint", "cmath", "numbers", "sqlite3", "multiprocessing", "concurrent", "asyncio", "ctypes");

    // ---- Modules -----------------------------------------------------------

    private static final Pattern NO_MODULE = Pattern.compile("No module named '([A-Za-z_][A-Za-z0-9_.]*)'");

    private static Diagnosis moduleNotFound(String value, Context ctx) {
        Matcher m = NO_MODULE.matcher(value);
        if (!m.find()) {
            return Diagnosis.NONE;
        }
        String full = m.group(1);
        if (full.indexOf('.') >= 0) {
            // Python names the deepest module missing: the package itself is there
            return Diagnosis.NONE;
        }
        String local = localModuleFolder(ctx.folder(), full);
        if (local != null) {
            String statement = "import sys; sys.path.insert(0, '" + local + "')";
            return new Diagnosis("'" + full + "' is a module of yours, in " + local + "/: a folder the kernel does not search",
                List.of(new Edit("Add " + local + "/ to sys.path", "<html>Adds <code>" + escape(statement)
                                 + "</code> at the top of this cell, then runs it again</html>",
                                 List.of(addLine(ctx.source(), statement)))));
        }
        String elsewhere = NOT_ON_PYPI.get(full);
        if (elsewhere != null) {
            return new Diagnosis(elsewhere, List.of());
        }
        String pip = PIP_NAMES.getOrDefault(full, ON_PYPI.contains(full) ? full : null);
        if (pip != null) {
            return new Diagnosis("The kernel's Python has no '" + full + "'"
                                 + (pip.equalsIgnoreCase(full) ? "" : ": it comes in the package '" + pip + "'"),
                List.of(new Install("Install " + pip, "<html>pip install <b>" + escape(pip)
                                    + "</b> into the kernel's own Python, then this cell runs again</html>", pip)));
        }
        List<String> known = new ArrayList<>(ON_PYPI);
        known.addAll(PIP_NAMES.keySet());
        known.addAll(STDLIB);
        String close = closest(full, known);
        if (close != null) {
            List<Span> at = occurrences(ctx.source(), full, false);
            if (!at.isEmpty()) {
                return new Diagnosis("No module named '" + full + "': did you mean '" + close + "'?",
                    List.of(new Edit("Use '" + close + "'", "Replaces '" + full + "' with '" + close + "', then runs the cell again",
                                     replaced(at, close))));
            }
        }
        String command = "python -m pip install " + full;
        return new Diagnosis("'" + full + "' is not a package Sphere knows: check its name on PyPI before installing it",
                             List.of(new Copy("Copy the pip command", "Copies '" + command + "'", command)));
    }

    /** Where import names and PyPI names part. */
    private static final Map<String, String> PIP_NAMES = Map.ofEntries(
        Map.entry("sklearn", "scikit-learn"), Map.entry("skimage", "scikit-image"), Map.entry("PIL", "pillow"),
        Map.entry("cv2", "opencv-python"), Map.entry("yaml", "pyyaml"), Map.entry("bs4", "beautifulsoup4"),
        Map.entry("dateutil", "python-dateutil"), Map.entry("dotenv", "python-dotenv"), Map.entry("serial", "pyserial"),
        Map.entry("zmq", "pyzmq"), Map.entry("attr", "attrs"), Map.entry("jwt", "PyJWT"), Map.entry("Crypto", "pycryptodome"),
        Map.entry("OpenGL", "PyOpenGL"), Map.entry("docx", "python-docx"), Map.entry("pptx", "python-pptx"),
        Map.entry("fitz", "pymupdf"), Map.entry("boost_histogram", "boost-histogram"), Map.entry("pythia8", "pythia8mc"),
        Map.entry("geant4_pybind", "geant4-pybind"), Map.entry("XRootD", "xrootd"), Map.entry("umap", "umap-learn"),
        Map.entry("pywt", "PyWavelets"), Map.entry("faiss", "faiss-cpu"), Map.entry("pyro", "pyro-ppl"),
        Map.entry("tensorflow_probability", "tensorflow-probability"), Map.entry("pytorch_lightning", "pytorch-lightning"),
        Map.entry("torch_geometric", "torch-geometric"), Map.entry("IPython", "ipython"), Map.entry("win32api", "pywin32"),
        Map.entry("scienceplots", "SciencePlots"), Map.entry("Bio", "biopython"), Map.entry("mpl_toolkits", "matplotlib"), Map.entry("lal", "lalsuite"), Map.entry("zfit_physics", "zfit-physics"));

    /** Packages whose import name is their PyPI name: the ones a physicist's notebook reaches for. */
    private static final Set<String> ON_PYPI = Set.of(
        "numpy", "scipy", "pandas", "matplotlib", "seaborn", "sympy", "statsmodels", "numba", "h5py", "tables", "xarray",
        "dask", "polars", "pyarrow", "networkx", "tqdm", "rich", "ipywidgets", "plotly", "bokeh", "altair", "requests",
        "httpx", "joblib", "lmfit", "emcee", "corner", "astropy", "healpy", "iminuit", "uproot", "awkward", "hist",
        "mplhep", "vector", "particle", "pylhe", "coffea", "pyhf", "zfit", "hepunits", "decaylanguage", "fastjet",
        "numexpr", "toml", "tomli", "jinja2", "click", "typer", "sqlalchemy", "psutil", "pydantic", "openpyxl", "xlrd",
        "xgboost", "lightgbm", "catboost", "torch", "torchvision", "torchaudio", "tensorflow", "keras", "jax", "flax",
        "optax", "transformers", "datasets", "onnx", "onnxruntime", "lxml", "pyhepmc", "uncertainties", "pint",
        "numdifftools", "mpmath", "gmpy2", "tabulate", "pygments", "sortedcontainers", "cachetools", "regex", "msgpack",
        "cloudpickle", "dill", "zstandard", "lz4", "xxhash", "cramjam", "fsspec", "pyvista", "vtk", "trimesh",
        "ipympl", "nbformat", "nbconvert", "papermill", "mlflow", "wandb", "optuna", "shap", "lime", "hdbscan",
        "gensim", "nltk", "spacy", "einops", "timm", "lightning", "graphviz", "pydot", "folium", "geopandas",
        "shapely", "pyproj", "netCDF4", "cftime", "soundfile", "librosa", "imageio", "tifffile", "pydicom", "nibabel",
        "cmasher", "colorcet", "tensorboard", "pymc", "arviz", "numpyro", "nflows", "zuko", "cppyy", "pytest",
        "hepstats", "resample", "gvar", "lsqfit", "pyccl", "camb", "classy");

    /** Modules that pip does not give: what does. */
    private static final Map<String, String> NOT_ON_PYPI = Map.of(
        "ROOT", "PyROOT comes with a ROOT installation, not with pip: run the notebook on a Python that has ROOT "
                + "(conda install -c conda-forge root), or source thisroot before starting Sphere",
        "lhapdf", "LHAPDF's Python module comes with an LHAPDF build (conda install -c conda-forge lhapdf)",
        "Geant4", "Geant4's Python bindings are 'geant4_pybind' on PyPI (pip install geant4-pybind), or come with a "
                  + "Geant4 built with GEANT4_USE_PYTHON",
        "madgraph", "MadGraph is not a Python package: Sphere drives it from :mg5",
        "pythia8mc", "The package pythia8mc is imported as 'pythia8'");

    /**
     * The folder of a module of one's own (name.py, or a package folder) under the notebook's folder,
     * relative to it with '/': the folder to put on sys.path. Null when there is none.
     */
    static String localModuleFolder(Path folder, String module) {
        if (folder == null || !Files.isDirectory(folder)) {
            return null;
        }
        Path[] hit = new Path[1];
        try {
            Files.walkFileTree(folder, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 4, new SimpleFileVisitor<>() {
                int visits;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (++visits > 20_000) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!dir.equals(folder) && skipped(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(folder) && dir.getFileName().toString().equals(module)
                        && Files.isRegularFile(dir.resolve("__init__.py"))) {
                        hit[0] = dir.getParent();
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visits > 20_000) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (file.getFileName().toString().equals(module + ".py")) {
                        hit[0] = file.getParent();
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unreadable) {
            return null;
        }
        if (hit[0] == null || hit[0].equals(folder)) {
            return null; // the kernel's own folder is searched already: something else is wrong
        }
        return slashes(folder.relativize(hit[0]).toString());
    }

    // ---- Python's own suggestions ------------------------------------------

    private static final Pattern CANNOT_IMPORT = Pattern.compile("cannot import name '([A-Za-z_][A-Za-z0-9_]*)'");
    private static final Pattern NO_ATTRIBUTE = Pattern.compile("has no attribute '([A-Za-z_][A-Za-z0-9_]*)'");

    private static Diagnosis importError(String value, Context ctx) {
        Matcher m = CANNOT_IMPORT.matcher(value);
        Matcher mean = MEAN.matcher(value);
        if (!m.find() || !mean.find()) {
            return Diagnosis.NONE;
        }
        String wrong = m.group(1);
        String right = mean.group(1);
        List<Span> at = occurrences(ctx.source(), wrong, false);
        if (at.isEmpty() || right.indexOf('.') >= 0) {
            return Diagnosis.NONE;
        }
        return new Diagnosis("Did you mean '" + right + "'?", List.of(new Edit("Use '" + right + "'",
            "Replaces '" + wrong + "' with '" + right + "', then runs the cell again", replaced(at, right))));
    }

    private static Diagnosis attributeError(String value, Context ctx) {
        Matcher m = NO_ATTRIBUTE.matcher(value);
        Matcher mean = MEAN.matcher(value);
        if (!m.find() || !mean.find()) {
            return Diagnosis.NONE;
        }
        String wrong = m.group(1);
        String right = mean.group(1);
        if (right.indexOf('.') >= 0) {
            return Diagnosis.NONE;
        }
        List<Span> at = occurrences(ctx.source(), wrong, true);
        // the failing line when the traceback names it: another object may own the same attribute elsewhere
        List<Span> onLine = onLine(ctx.source(), at, ctx.line());
        if (!onLine.isEmpty()) {
            at = onLine;
        }
        if (at.isEmpty()) {
            return Diagnosis.NONE;
        }
        return new Diagnosis("Did you mean '." + right + "'?", List.of(new Edit("Use '." + right + "'",
            "Replaces '." + wrong + "' with '." + right + "', then runs the cell again", replaced(at, right))));
    }

    // ---- Files -------------------------------------------------------------

    private static final Pattern NO_FILE = Pattern.compile("No such file or directory: '((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern NO_FILE_DQ = Pattern.compile("No such file or directory: \"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern NUMPY_NOT_FOUND = Pattern.compile("(?m)^(\\S[^\\n]*?) not found\\.?$");
    private static final Pattern QUOTED_FILE = Pattern.compile("'([^'\\n]+\\.[A-Za-z0-9]{1,8})'");

    private static Diagnosis fileNotFound(String value, Context ctx) {
        String missing = null;
        for (Pattern p : List.of(NO_FILE, NO_FILE_DQ, NUMPY_NOT_FOUND, QUOTED_FILE)) {
            Matcher m = p.matcher(value);
            if (m.find()) {
                missing = m.group(1).strip();
                break;
            }
        }
        if (missing == null || missing.isEmpty()) {
            return Diagnosis.NONE;
        }
        String plain = missing.replace("\\\\", "\\"); // Python shows backslashes doubled
        String name = fileName(plain);
        if (name.isEmpty() || ctx.folder() == null) {
            return Diagnosis.NONE;
        }
        List<Found> found = findFiles(ctx.folder(), name, 5, 30_000);
        String where = ctx.folder().getFileName() == null ? ctx.folder().toString() : ctx.folder().getFileName().toString();
        if (found.isEmpty()) {
            return new Diagnosis("No file named '" + name + "' anywhere under " + where + "/ either", List.of());
        }
        List<Span> literal = new ArrayList<>();
        for (String form : new LinkedHashSet<>(List.of(missing, plain, slashes(plain)))) {
            int from = 0;
            int at;
            while (!form.isEmpty() && (at = ctx.source().indexOf(form, from)) >= 0) {
                literal.add(new Span(at, at + form.length(), null));
                from = at + form.length();
            }
            if (!literal.isEmpty()) {
                break;
            }
        }
        List<Fix> fixes = new ArrayList<>();
        for (Found f : found.subList(0, Math.min(2, found.size()))) {
            String rel = slashes(ctx.folder().relativize(f.path()).toString());
            fixes.add(literal.isEmpty()
                ? new Copy("Copy '" + rel + "'", "Copies the path the kernel would find", rel)
                : new Edit("Use '" + rel + "'", "Puts this path in the cell, then runs it again", replaced(literal, rel)));
        }
        Found best = found.get(0);
        String rel = slashes(ctx.folder().relativize(best.path()).toString());
        return new Diagnosis("'" + plain + "' is not where the kernel looks (" + where + "/): "
                             + (best.distance() == 0 ? "a file of that name is at " : "a close name is at ") + rel, fixes);
    }

    /** A file found, and how far its name is from the one asked for (0: the same name). */
    record Found(Path path, int distance, int depth) {
    }

    /**
     * Files under a folder named as asked (ignoring case), else with a close name and the same
     * extension: nearest names first, then shallowest. Skips hidden folders, caches and environments.
     */
    static List<Found> findFiles(Path folder, String name, int maxDepth, int maxVisits) {
        List<Found> out = new ArrayList<>();
        if (folder == null || !Files.isDirectory(folder)) {
            return out;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        int dot = wanted.lastIndexOf('.');
        String stem = dot > 0 ? wanted.substring(0, dot) : wanted;
        String ext = dot > 0 ? wanted.substring(dot) : "";
        try {
            Files.walkFileTree(folder, EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
                int visits;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (++visits > maxVisits) {
                        return FileVisitResult.TERMINATE;
                    }
                    return !dir.equals(folder) && skipped(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visits > maxVisits) {
                        return FileVisitResult.TERMINATE;
                    }
                    String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    int depth = folder.relativize(file).getNameCount();
                    if (n.equals(wanted)) {
                        out.add(new Found(file, 0, depth));
                    } else if (!ext.isEmpty() && n.endsWith(ext) && stem.length() >= 3) {
                        int d = distance(stem, n.substring(0, n.length() - ext.length()));
                        if (d <= (stem.length() <= 5 ? 1 : 2)) {
                            out.add(new Found(file, d, depth));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unreadable) {
            return out;
        }
        out.sort((a, b) -> a.distance() != b.distance() ? Integer.compare(a.distance(), b.distance())
                         : a.depth() != b.depth() ? Integer.compare(a.depth(), b.depth())
                         : a.path().toString().compareTo(b.path().toString()));
        return out;
    }

    private static boolean skipped(Path dir) {
        String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return n.startsWith(".") || n.equals("node_modules") || n.equals("__pycache__") || n.equals("venv")
               || n.equals("env") || n.equals("site-packages") || n.equals("dist-packages") || n.equals("$RECYCLE.BIN")
               || n.equals("System Volume Information");
    }

    private static String fileName(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(cut + 1).strip();
    }

    private static String slashes(String path) {
        return path.replace('\\', '/');
    }

    // ---- Syntax ------------------------------------------------------------

    private static final Pattern LINE_IN_VALUE = Pattern.compile("line (\\d+)\\)?\\s*$");
    private static final Pattern AFTER_HEADER = Pattern.compile("expected an indented block after .* on line (\\d+)");

    private static int failingLine(String value, Context ctx) {
        if (ctx.line() >= 1) {
            return ctx.line();
        }
        Matcher m = LINE_IN_VALUE.matcher(value);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static Diagnosis syntaxError(String value, Context ctx) {
        if (!value.startsWith("expected ':'")) {
            return Diagnosis.NONE;
        }
        int line = failingLine(value, ctx);
        int at = endOfCode(ctx.source(), line);
        if (at < 0 || at > 0 && ctx.source().charAt(at - 1) == ':') {
            return Diagnosis.NONE;
        }
        return new Diagnosis("Line " + line + " opens a block: it ends with ':'",
            List.of(new Edit("Add ':'", "Adds the colon line " + line + " lacks, then runs the cell again",
                             List.of(new Span(at, at, ":")))));
    }

    private static Diagnosis indentationError(String value, Context ctx) {
        String src = ctx.source();
        Matcher header = AFTER_HEADER.matcher(value);
        if (header.find()) {
            int h = Integer.parseInt(header.group(1));
            int line = ctx.line() >= 1 && ctx.line() != h ? ctx.line() : nextCodeLine(src, h);
            int start = lineStart(src, line);
            int hs = lineStart(src, h);
            if (start < 0 || hs < 0) {
                return Diagnosis.NONE;
            }
            String indent = leading(src, hs) + "    ";
            String now = leading(src, start);
            return new Diagnosis("Line " + line + " belongs to the block line " + h + " opens: it is indented under it",
                List.of(new Edit("Indent line " + line, "Indents line " + line + " under line " + h + ", then runs the cell again",
                                 List.of(new Span(start, start + now.length(), indent)))));
        }
        if (value.startsWith("unexpected indent")) {
            int line = failingLine(value, ctx);
            int start = lineStart(src, line);
            if (start < 0) {
                return Diagnosis.NONE;
            }
            String before = "";
            for (int k = line - 1; k >= 1; k--) {
                int s = lineStart(src, k);
                String text = lineText(src, s);
                if (!text.isBlank() && !text.strip().startsWith("#")) {
                    before = leading(src, s);
                    break;
                }
            }
            String now = leading(src, start);
            if (now.length() <= before.length()) {
                return Diagnosis.NONE;
            }
            return new Diagnosis("Line " + line + " is indented more than the code above it",
                List.of(new Edit("Align line " + line, "Gives line " + line + " the indent of the line above, then runs the cell again",
                                 List.of(new Span(start, start + now.length(), before)))));
        }
        return Diagnosis.NONE;
    }

    // ---- Text --------------------------------------------------------------

    /** Offset where a 1-based line starts, -1 past the end. */
    static int lineStart(String s, int line) {
        if (line < 1) {
            return -1;
        }
        int at = 0;
        for (int k = 1; k < line; k++) {
            int nl = s.indexOf('\n', at);
            if (nl < 0) {
                return -1;
            }
            at = nl + 1;
        }
        return at;
    }

    private static String lineText(String s, int start) {
        int end = s.indexOf('\n', start);
        return s.substring(start, end < 0 ? s.length() : end);
    }

    private static String leading(String s, int start) {
        int i = start;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(start, i);
    }

    private static int nextCodeLine(String s, int after) {
        int line = after + 1;
        int start;
        while ((start = lineStart(s, line)) >= 0) {
            String t = lineText(s, start).strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                return line;
            }
            line++;
        }
        return after + 1;
    }

    /** The offset just after the last code character of a line, before its comment; -1 when the line has none. */
    static int endOfCode(String s, int line) {
        int start = lineStart(s, line);
        if (start < 0) {
            return -1;
        }
        int end = s.indexOf('\n', start);
        end = end < 0 ? s.length() : end;
        byte[] kinds = kinds(s);
        int last = -1;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (kinds[i] == COMMENT) {
                break;
            }
            if (!Character.isWhitespace(c)) {
                last = i;
            }
        }
        return last < 0 ? -1 : last + 1;
    }

    /**
     * Where an import goes in a cell: after its leading comments, blank lines and magics
     * (%, !), so a cell starting with %matplotlib inline keeps it first.
     */
    static Span addLine(String source, String statement) {
        int at = 0;
        while (at < source.length()) {
            int nl = source.indexOf('\n', at);
            String line = source.substring(at, nl < 0 ? source.length() : nl).strip();
            if (!(line.isEmpty() || line.startsWith("#") || line.startsWith("%") || line.startsWith("!"))) {
                break;
            }
            if (nl < 0) {
                return new Span(source.length(), source.length(), (source.isEmpty() ? "" : "\n") + statement);
            }
            at = nl + 1;
        }
        return new Span(at, at, statement + "\n");
    }

    private static final byte CODE = 0;
    private static final byte STRING = 1;
    private static final byte COMMENT = 2;

    /** Which characters of Python source are code: not in a string, not in a comment. */
    static boolean[] codeMask(String s) {
        byte[] k = kinds(s);
        boolean[] code = new boolean[k.length];
        for (int i = 0; i < k.length; i++) {
            code[i] = k[i] == CODE;
        }
        return code;
    }

    /** What each character of Python source is part of: code, a string (quotes included), a comment. */
    static byte[] kinds(String s) {
        byte[] kind = new byte[s.length()];
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '#') {
                while (i < n && s.charAt(i) != '\n') {
                    kind[i++] = COMMENT;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                boolean triple = i + 2 < n && s.charAt(i + 1) == c && s.charAt(i + 2) == c;
                int j = i + (triple ? 3 : 1);
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (triple) {
                        if (d == c && j + 2 < n && s.charAt(j + 1) == c && s.charAt(j + 2) == c) {
                            j += 3;
                            break;
                        }
                    } else {
                        if (d == c) {
                            j++;
                            break;
                        }
                        if (d == '\n') {
                            break;
                        }
                    }
                    j++;
                }
                int stop = Math.min(j, n);
                while (i < stop) {
                    kind[i++] = STRING;
                }
                continue;
            }
            kind[i++] = CODE;
        }
        return kind;
    }

    /**
     * Where a name stands as code: whole words outside strings and comments. As an attribute,
     * only after a dot; otherwise never after a dot, nor as a keyword argument (f(x=1)), which
     * names the function's parameter rather than the variable.
     */
    static List<Span> occurrences(String s, String word, boolean attribute) {
        List<Span> out = new ArrayList<>();
        if (word == null || word.isEmpty()) {
            return out;
        }
        boolean[] code = codeMask(s);
        int[] depth = new int[s.length() + 1];
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            depth[i] = d;
            if (code[i]) {
                char c = s.charAt(i);
                if (c == '(' || c == '[' || c == '{') {
                    d++;
                } else if ((c == ')' || c == ']' || c == '}') && d > 0) {
                    d--;
                }
            }
        }
        int from = 0;
        int at;
        while ((at = s.indexOf(word, from)) >= 0) {
            from = at + word.length();
            int end = at + word.length();
            if (!code[at] || at > 0 && isIdent(s.charAt(at - 1)) || end < s.length() && isIdent(s.charAt(end))) {
                continue;
            }
            int p = at - 1;
            while (p >= 0 && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) {
                p--;
            }
            boolean afterDot = p >= 0 && s.charAt(p) == '.';
            if (attribute != afterDot) {
                continue;
            }
            if (!attribute && depth[at] > 0) {
                int q = end;
                while (q < s.length() && (s.charAt(q) == ' ' || s.charAt(q) == '\t')) {
                    q++;
                }
                if (q < s.length() && s.charAt(q) == '=' && (q + 1 >= s.length() || s.charAt(q + 1) != '=')) {
                    continue; // a keyword argument
                }
            }
            out.add(new Span(at, end, word));
        }
        return out;
    }

    private static List<Span> onLine(String s, List<Span> spans, int line) {
        int start = lineStart(s, line);
        if (start < 0) {
            return List.of();
        }
        int end = s.indexOf('\n', start);
        int stop = end < 0 ? s.length() : end;
        List<Span> out = new ArrayList<>();
        for (Span sp : spans) {
            if (sp.start() >= start && sp.end() <= stop) {
                out.add(sp);
            }
        }
        return out;
    }

    private static List<Span> replaced(List<Span> spans, String text) {
        List<Span> out = new ArrayList<>(spans.size());
        for (Span sp : spans) {
            out.add(new Span(sp.start(), sp.end(), text));
        }
        return out;
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ---- Names -------------------------------------------------------------

    static String closest(String word, Collection<String> candidates) {
        return closest(word, candidates, 3);
    }

    /**
     * The candidate nearest a misspelled name, if near enough: one edit up to five letters, two up
     * to nine, three beyond (never more than cap); a difference of case alone wins. Candidates come
     * in order of preference: on a tie the first stays.
     */
    static String closest(String word, Collection<String> candidates, int cap) {
        if (word == null || word.isEmpty()) {
            return null;
        }
        int limit = Math.min(cap, word.length() <= 2 ? 0 : word.length() <= 5 ? 1 : word.length() <= 9 ? 2 : 3);
        String best = null;
        int bestD = limit + 1;
        for (String c : candidates) {
            if (c == null || c.equals(word) || Math.abs(c.length() - word.length()) > Math.max(limit, 0)
                && !c.equalsIgnoreCase(word)) {
                continue;
            }
            if (c.equalsIgnoreCase(word)) {
                return c;
            }
            int d = distance(word, c);
            if (d < bestD) {
                best = c;
                bestD = d;
            }
        }
        return bestD <= limit ? best : null;
    }

    /** Edits from one word to another: insertions, deletions, substitutions, swaps of neighbours. */
    static int distance(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
