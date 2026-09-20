package com.sphere.core.commands;

import com.sphere.utils.AppLogger;

/**
 * Particles, four-vectors, phase space, matrices and limits.
 *
 * Split out of Handlers, which had grown to hold every backend at once. The
 * helpers these handlers share -- the argument readers, the interpreter call,
 * the named-handle builders -- stay in Handlers and are called through it.
 */
public final class RootPhysicsCommands {

    private RootPhysicsCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void rootLimitChi2Test(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root limit chi2test"));
        if (w.length < 2) {
            Handlers.usage(":root limit chi2test <a> <b> [option]".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", w[0]) + "->Chi2Test(" + Handlers.obj("TH1", w[1]) + ", \"" + (w.length > 2 ? w[2] : "UU") + "\")");
    }

    public static void rootLimitFeldman(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root limit feldman"));
        if (w.length < 2) {
            Handlers.usage(":root limit feldman <observed> <background> [confidence]".trim());
            return;
        }
        Handlers.cling(c, "[]{ TFeldmanCousins f(" + (w.length > 2 ? w[2] : "0.9") + "); return std::to_string(f.CalculateLowerLimit(" + Handlers.csv(w[0] + " " + w[1]) + ")) + \" .. \" + std::to_string(f.CalculateUpperLimit(" + Handlers.csv(w[0] + " " + w[1]) + ")); }()");
    }

    public static void rootLimitPvalue(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root limit pvalue"));
        if (w.length < 2) {
            Handlers.usage(":root limit pvalue <chi2> <ndf>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Prob(" + Handlers.csv(Handlers.join(w, 0)) + ")");
    }

    public static void rootLimitSignificance(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root limit significance"));
        if (w.length < 2) {
            Handlers.usage(":root limit significance <signal> <background>".trim());
            return;
        }
        Handlers.cling(c, "[]{ const double s = " + w[0] + ", b = " + w[1] + "; return (b <= 0.0) ? 0.0 : TMath::Sqrt(2.0 * ((s + b) * TMath::Log(1.0 + s / b) - s)); }()");
    }

    public static void rootLimitZvalue(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root limit zvalue"));
        if (w.length < 1) {
            Handlers.usage(":root limit zvalue <p>".trim());
            return;
        }
        Handlers.cling(c, "TMath::NormQuantile(1.0 - " + w[0] + ")");
    }

    public static void rootMatrixDet(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix det"));
        if (w.length < 1) {
            Handlers.usage(":root matrix det <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TMatrixD") + "->Determinant()");
    }

    public static void rootMatrixGet(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix get"));
        if (w.length < 3) {
            Handlers.usage(":root matrix get <name> <row> <col>".trim());
            return;
        }
        Handlers.cling(c, "(*" + Handlers.held(w[0], "TMatrixD") + ")(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootMatrixInvert(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix invert"));
        if (w.length < 1) {
            Handlers.usage(":root matrix invert <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMatrixD") + "->Invert(), std::string(\"inverted\"))");
    }

    public static void rootMatrixNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix new"));
        if (w.length < 3) {
            Handlers.usage(":root matrix new <name> <rows> <cols>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TMatrixD", "new TMatrixD(" + Handlers.csv(Handlers.join(w, 1)) + ")"));
    }

    public static void rootMatrixPrint(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix print"));
        if (w.length < 1) {
            Handlers.usage(":root matrix print <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TMatrixD") + "->Print()");
    }

    public static void rootMatrixSet(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix set"));
        if (w.length < 4) {
            Handlers.usage(":root matrix set <name> <row> <col> <value>".trim());
            return;
        }
        Handlers.cling(c, "((*" + Handlers.held(w[0], "TMatrixD") + ")(" + Handlers.csv(w[1] + " " + w[2]) + ") = " + w[3] + ", std::string(\"set\"))");
    }

    public static void rootMatrixTranspose(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix transpose"));
        if (w.length < 1) {
            Handlers.usage(":root matrix transpose <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMatrixD") + "->T(), std::string(\"transposed\"))");
    }

    public static void rootMatrixUnit(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix unit"));
        if (w.length < 1) {
            Handlers.usage(":root matrix unit <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMatrixD") + "->UnitMatrix(), std::string(\"unit\"))");
    }

    public static void rootPdgCharge(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdg charge"));
        if (w.length < 1) {
            Handlers.usage(":root pdg charge <particle>".trim());
            return;
        }
        Handlers.cling(c, "TDatabasePDG::Instance()->GetParticle(\"" + w[0] + "\")->Charge()");
    }

    public static void rootPdgClass(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdg class"));
        if (w.length < 1) {
            Handlers.usage(":root pdg class <particle>".trim());
            return;
        }
        Handlers.cling(c, "TDatabasePDG::Instance()->GetParticle(\"" + w[0] + "\")->ParticleClass()");
    }

    public static void rootPdgCode(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdg code"));
        if (w.length < 1) {
            Handlers.usage(":root pdg code <particle>".trim());
            return;
        }
        Handlers.cling(c, "TDatabasePDG::Instance()->GetParticle(\"" + w[0] + "\")->PdgCode()");
    }

    public static void rootPdgLifetime(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdg lifetime"));
        if (w.length < 1) {
            Handlers.usage(":root pdg lifetime <particle>".trim());
            return;
        }
        Handlers.cling(c, "TDatabasePDG::Instance()->GetParticle(\"" + w[0] + "\")->Lifetime()");
    }

    public static void rootPdgMass(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdg mass"));
        if (w.length < 1) {
            Handlers.usage(":root pdg mass <particle>".trim());
            return;
        }
        Handlers.cling(c, "TDatabasePDG::Instance()->GetParticle(\"" + w[0] + "\")->Mass()");
    }

    public static void rootPdgName(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdg name"));
        if (w.length < 1) {
            Handlers.usage(":root pdg name <code>".trim());
            return;
        }
        Handlers.cling(c, "TDatabasePDG::Instance()->GetParticle(" + Handlers.asInt(w[0], 0) + ")->GetName()");
    }

    public static void rootPdgPrint(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdg print"));
        if (w.length < 1) {
            Handlers.usage(":root pdg print <particle>".trim());
            return;
        }
        Handlers.cling(c, "TDatabasePDG::Instance()->GetParticle(\"" + w[0] + "\")->Print()");
    }

    public static void rootPhaseGenerate(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root phase generate"));
        if (w.length < 1) {
            Handlers.usage(":root phase generate <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TGenPhaseSpace") + "->Generate()");
    }

    public static void rootPhaseKeep(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root phase keep"));
        if (w.length < 3) {
            Handlers.usage(":root phase keep <name> <index> <vector>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[2], "TLorentzVector", "new TLorentzVector(*" + Handlers.held(w[0], "TGenPhaseSpace") + "->GetDecay(" + Handlers.asInt(w[1], 0) + "))"));
    }

    public static void rootPhaseMass(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root phase mass"));
        if (w.length < 2) {
            Handlers.usage(":root phase mass <name> <index>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TGenPhaseSpace") + "->GetDecay(" + Handlers.asInt(w[1], 0) + ")->M()");
    }

    public static void rootPhaseNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root phase new"));
        if (w.length < 3) {
            Handlers.usage(":root phase new <name> <energy> <mass> <mass> ...".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TGenPhaseSpace", "[]{ auto *g = new TGenPhaseSpace(); TLorentzVector parent(0,0,0," + w[1] + "); double masses[] = {" + Handlers.csv(Handlers.join(w, 2)) + "}; g->SetDecay(parent, " + (w.length - 2) + ", masses); return g; }()"));
    }

    public static void rootRandExp(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rand exp"));
        if (w.length < 2) {
            Handlers.usage(":root rand exp <name> <tau>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TRandom3") + "->Exp(" + w[1] + ")");
    }

    public static void rootRandGaus(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rand gaus"));
        if (w.length < 3) {
            Handlers.usage(":root rand gaus <name> <mean> <sigma>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TRandom3") + "->Gaus(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootRandInteger(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rand integer"));
        if (w.length < 2) {
            Handlers.usage(":root rand integer <name> <max>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TRandom3") + "->Integer(" + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootRandLandau(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rand landau"));
        if (w.length < 3) {
            Handlers.usage(":root rand landau <name> <mpv> <sigma>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TRandom3") + "->Landau(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootRandNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rand new"));
        if (w.length < 1) {
            Handlers.usage(":root rand new <name> [seed]".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TRandom3", "new TRandom3(" + Handlers.asInt((w.length > 1 ? w[1] : "0"), 0) + ")"));
    }

    public static void rootRandPoisson(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rand poisson"));
        if (w.length < 2) {
            Handlers.usage(":root rand poisson <name> <mean>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TRandom3") + "->Poisson(" + w[1] + ")");
    }

    public static void rootRandSeed(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rand seed"));
        if (w.length < 2) {
            Handlers.usage(":root rand seed <name> <n>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TRandom3") + "->SetSeed(" + Handlers.asInt(w[1], 0) + "), std::string(\"seed set\"))");
    }

    public static void rootRandUniform(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root rand uniform"));
        if (w.length < 1) {
            Handlers.usage(":root rand uniform <name> [max]".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TRandom3") + "->Uniform(" + (w.length > 1 ? w[1] : "1") + ")");
    }

    public static void rootVecAdd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec add"));
        if (w.length < 2) {
            Handlers.usage(":root vec add <into> <from>".trim());
            return;
        }
        Handlers.cling(c, "(*" + Handlers.held(w[0], "TLorentzVector") + " += *" + Handlers.held(w[1], "TLorentzVector") + ", " + Handlers.held(w[0], "TLorentzVector") + "->M())");
    }

    public static void rootVecBoost(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec boost"));
        if (w.length < 4) {
            Handlers.usage(":root vec boost <name> <bx> <by> <bz>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TLorentzVector") + "->Boost(" + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"boosted\"))");
    }

    public static void rootVecDeltaR(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec deltar"));
        if (w.length < 2) {
            Handlers.usage(":root vec deltar <a> <b>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TLorentzVector") + "->DeltaR(*" + Handlers.held(w[1], "TLorentzVector") + ")");
    }

    public static void rootVecEnergy(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec energy"));
        if (w.length < 1) {
            Handlers.usage(":root vec energy <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TLorentzVector") + "->E()");
    }

    public static void rootVecEta(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec eta"));
        if (w.length < 1) {
            Handlers.usage(":root vec eta <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TLorentzVector") + "->Eta()");
    }

    public static void rootVecMass(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec mass"));
        if (w.length < 1) {
            Handlers.usage(":root vec mass <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TLorentzVector") + "->M()");
    }

    public static void rootVecNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec new"));
        if (w.length < 5) {
            Handlers.usage(":root vec new <name> <px> <py> <pz> <e>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TLorentzVector", "new TLorentzVector(" + Handlers.csv(Handlers.join(w, 1)) + ")"));
    }

    public static void rootVecP(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec p"));
        if (w.length < 1) {
            Handlers.usage(":root vec p <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TLorentzVector") + "->P()");
    }

    public static void rootVecPhi(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec phi"));
        if (w.length < 1) {
            Handlers.usage(":root vec phi <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TLorentzVector") + "->Phi()");
    }

    public static void rootVecPrint(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec print"));
        if (w.length < 1) {
            Handlers.usage(":root vec print <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TLorentzVector") + "->Print()");
    }

    public static void rootVecPt(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec pt"));
        if (w.length < 1) {
            Handlers.usage(":root vec pt <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TLorentzVector") + "->Pt()");
    }

    public static void rootVecPtEtaPhiM(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root vec ptetaphim"));
        if (w.length < 5) {
            Handlers.usage(":root vec ptetaphim <name> <pt> <eta> <phi> <m>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TLorentzVector", "[]{ auto *v = new TLorentzVector(); v->SetPtEtaPhiM(" + Handlers.csv(Handlers.join(w, 1)) + "); return v; }()"));
    }

    // --- ROOT::Math GenVector, the vectors that are not legacy ---

    public static void rootGvNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv new"));
        if (w.length < 5) {
            Handlers.usage(":root gv new <name> <px> <py> <pz> <e>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\", " + "new ROOT::Math::PtEtaPhiMVector(ROOT::Math::PxPyPzEVector(" + Handlers.csv(Handlers.join(w, 1)) + "))" + ", \"ROOT::Math::PtEtaPhiMVector\")");
    }

    public static void rootGvPtEtaPhiM(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv ptetaphim"));
        if (w.length < 5) {
            Handlers.usage(":root gv ptetaphim <name> <pt> <eta> <phi> <m>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\", " + "new ROOT::Math::PtEtaPhiMVector(" + Handlers.csv(Handlers.join(w, 1)) + ")" + ", \"ROOT::Math::PtEtaPhiMVector\")");
    }

    public static void rootGvPtEtaPhiE(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv ptetaphie"));
        if (w.length < 5) {
            Handlers.usage(":root gv ptetaphie <name> <pt> <eta> <phi> <e>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\", " + "new ROOT::Math::PtEtaPhiMVector(ROOT::Math::PtEtaPhiEVector(" + Handlers.csv(Handlers.join(w, 1)) + "))" + ", \"ROOT::Math::PtEtaPhiMVector\")");
    }

    public static void rootGvPt(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv pt"));
        if (w.length < 1) {
            Handlers.usage(":root gv pt <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Pt()");
    }

    public static void rootGvEta(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv eta"));
        if (w.length < 1) {
            Handlers.usage(":root gv eta <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Eta()");
    }

    public static void rootGvPhi(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv phi"));
        if (w.length < 1) {
            Handlers.usage(":root gv phi <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Phi()");
    }

    public static void rootGvM(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv mass"));
        if (w.length < 1) {
            Handlers.usage(":root gv mass <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->M()");
    }

    public static void rootGvE(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv energy"));
        if (w.length < 1) {
            Handlers.usage(":root gv energy <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->E()");
    }

    public static void rootGvP(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv p"));
        if (w.length < 1) {
            Handlers.usage(":root gv p <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->P()");
    }

    public static void rootGvPx(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv px"));
        if (w.length < 1) {
            Handlers.usage(":root gv px <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Px()");
    }

    public static void rootGvPy(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv py"));
        if (w.length < 1) {
            Handlers.usage(":root gv py <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Py()");
    }

    public static void rootGvPz(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv pz"));
        if (w.length < 1) {
            Handlers.usage(":root gv pz <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Pz()");
    }

    public static void rootGvRapidity(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv rapidity"));
        if (w.length < 1) {
            Handlers.usage(":root gv rapidity <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Rapidity()");
    }

    public static void rootGvMt(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv mt"));
        if (w.length < 1) {
            Handlers.usage(":root gv mt <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Mt()");
    }

    public static void rootGvEt(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv et"));
        if (w.length < 1) {
            Handlers.usage(":root gv et <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Et()");
    }

    public static void rootGvPerp2(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv perp2"));
        if (w.length < 1) {
            Handlers.usage(":root gv perp2 <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Perp2()");
    }

    public static void rootGvAdd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv add"));
        if (w.length < 2) {
            Handlers.usage(":root gv add <into> <from>".trim());
            return;
        }
        Handlers.cling(c, "(*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + " += *" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[1] + "\")" + ", " + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->M())");
    }

    public static void rootGvScale(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv scale"));
        if (w.length < 2) {
            Handlers.usage(":root gv scale <name> <factor>".trim());
            return;
        }
        Handlers.cling(c, "(*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + " *= " + w[1] + ", " + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Pt())");
    }

    public static void rootGvBoost(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv boost"));
        if (w.length < 4) {
            Handlers.usage(":root gv boost <name> <bx> <by> <bz>".trim());
            return;
        }
        Handlers.cling(c, "(*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + " = ROOT::Math::PtEtaPhiMVector(ROOT::Math::Boost(" + Handlers.csv(Handlers.join(w, 1)) + ")(*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + ")), std::string(\"boosted\"))");
    }

    public static void rootGvDeltaR(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv deltar"));
        if (w.length < 2) {
            Handlers.usage(":root gv deltar <a> <b>".trim());
            return;
        }
        Handlers.cling(c, "ROOT::Math::VectorUtil::DeltaR(*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + ", *" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[1] + "\")" + ")");
    }

    public static void rootGvDeltaPhi(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv deltaphi"));
        if (w.length < 2) {
            Handlers.usage(":root gv deltaphi <a> <b>".trim());
            return;
        }
        Handlers.cling(c, "ROOT::Math::VectorUtil::DeltaPhi(*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + ", *" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[1] + "\")" + ")");
    }

    public static void rootGvDeltaEta(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv deltaeta"));
        if (w.length < 2) {
            Handlers.usage(":root gv deltaeta <a> <b>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + "->Eta() - " + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[1] + "\")" + "->Eta())");
    }

    public static void rootGvAngle(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv angle"));
        if (w.length < 2) {
            Handlers.usage(":root gv angle <a> <b>".trim());
            return;
        }
        Handlers.cling(c, "ROOT::Math::VectorUtil::Angle(*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + ", *" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[1] + "\")" + ")");
    }

    public static void rootGvInvMass(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv invmass"));
        if (w.length < 2) {
            Handlers.usage(":root gv invmass <a> <b>".trim());
            return;
        }
        Handlers.cling(c, "ROOT::Math::VectorUtil::InvariantMass(*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + ", *" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[1] + "\")" + ")");
    }

    public static void rootGvCosTheta(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv costheta"));
        if (w.length < 2) {
            Handlers.usage(":root gv costheta <a> <b>".trim());
            return;
        }
        Handlers.cling(c, "ROOT::Math::VectorUtil::CosTheta(*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")" + ", *" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[1] + "\")" + ")");
    }

    public static void rootGvPrint(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gv print"));
        if (w.length < 1) {
            Handlers.usage(":root gv print <name>".trim());
            return;
        }
        Handlers.cling(c, "*" + "SphereBridge::Held<ROOT::Math::PtEtaPhiMVector>(\"" + w[0] + "\")");
    }

    // --- RooStats, sparse histograms, splines, density estimation, principal components, unfolding, decompositions, integrators, interpolators, FFT, XML and compression ---

    public static void rootPcaNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pca new"));
        if (w.length < 2) {
            Handlers.usage(":root pca new <name> <dimensions>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TPrincipal>(\"" + w[0] + "\", " + "new TPrincipal(" + Handlers.asInt(w[1], 2) + ", \"ND\")" + ", \"TPrincipal\")");
    }

    public static void rootPcaAdd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pca add"));
        if (w.length < 3) {
            Handlers.usage(":root pca add <name> <x> <x> ...".trim());
            return;
        }
        Handlers.cling(c, "[]{ double row[] = {" + Handlers.csv(Handlers.join(w, 1)) + "}; " + "SphereBridge::Held<TPrincipal>(\"" + w[0] + "\")" + "->AddRow(row); return std::string(\"added\"); }()");
    }

    public static void rootPcaMake(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pca make"));
        if (w.length < 1) {
            Handlers.usage(":root pca make <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<TPrincipal>(\"" + w[0] + "\")" + "->MakePrincipals(), std::string(\"computed\"))");
    }

    public static void rootPcaPrint(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pca print"));
        if (w.length < 1) {
            Handlers.usage(":root pca print <name> [option]".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TPrincipal>(\"" + w[0] + "\")" + "->Print(\"" + (w.length > 1 ? w[1] : "MSE") + "\")");
    }

    public static void rootPcaEigen(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pca eigen"));
        if (w.length < 1) {
            Handlers.usage(":root pca eigen <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TPrincipal>(\"" + w[0] + "\")" + "->GetEigenValues()->Print()");
    }

    public static void rootPcaCovariance(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pca covariance"));
        if (w.length < 1) {
            Handlers.usage(":root pca covariance <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TPrincipal>(\"" + w[0] + "\")" + "->GetCovarianceMatrix()->Print()");
    }

    public static void rootDecompSvd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root decomp svd"));
        if (w.length < 1) {
            Handlers.usage(":root decomp svd <matrix>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TDecompSVD d(*" + "SphereBridge::Held<TMatrixD>(\"" + w[0] + "\")" + "); if (!d.Decompose()) { return std::string(\"ERROR: the matrix refused to decompose\"); } const TVectorD &s = d.GetSig(); std::string out; for (int i = 0; i < s.GetNrows(); ++i) { if (!out.empty()) { out += \" \"; } out += std::to_string(s(i)); } return out; }()");
    }

    public static void rootDecompLu(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root decomp lu"));
        if (w.length < 1) {
            Handlers.usage(":root decomp lu <matrix>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TDecompLU d(*" + "SphereBridge::Held<TMatrixD>(\"" + w[0] + "\")" + "); if (!d.Decompose()) { return std::string(\"ERROR: the matrix refused to decompose\"); } d.Print(); return std::string(\"\"); }()");
    }

    public static void rootDecompChol(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root decomp chol"));
        if (w.length < 1) {
            Handlers.usage(":root decomp chol <matrix>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TDecompChol d(*" + "SphereBridge::Held<TMatrixD>(\"" + w[0] + "\")" + "); if (!d.Decompose()) { return std::string(\"ERROR: the matrix is not positive definite\"); } d.Print(); return std::string(\"\"); }()");
    }

    public static void rootDecompQr(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root decomp qr"));
        if (w.length < 1) {
            Handlers.usage(":root decomp qr <matrix>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TDecompQRH d(*" + "SphereBridge::Held<TMatrixD>(\"" + w[0] + "\")" + "); if (!d.Decompose()) { return std::string(\"ERROR: the matrix refused to decompose\"); } d.Print(); return std::string(\"\"); }()");
    }

    public static void rootDecompSolve(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root decomp solve"));
        if (w.length < 3) {
            Handlers.usage(":root decomp solve <matrix> <b> <b> ...".trim());
            return;
        }
        Handlers.cling(c, "[]{ TVectorD b(" + (w.length - 1) + "); double rhs[] = {" + Handlers.csv(Handlers.join(w, 1)) + "}; for (int i = 0; i < b.GetNrows(); ++i) { b(i) = rhs[i]; } TDecompLU d(*" + "SphereBridge::Held<TMatrixD>(\"" + w[0] + "\")" + "); if (!d.Solve(b)) { return std::string(\"ERROR: the system has no solution\"); } std::string out; for (int i = 0; i < b.GetNrows(); ++i) { if (!out.empty()) { out += \" \"; } out += std::to_string(b(i)); } return out; }()");
    }

    public static void rootIntegrateExpr(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root integrate expr"));
        if (w.length < 3) {
            Handlers.usage(":root integrate expr <from> <to> <expression>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TF1 f(\"sphere_integrand\", \"" + Handlers.join(w, 2) + "\", " + Handlers.csv(w[0] + ' ' + w[1]) + "); return f.Integral(" + Handlers.csv(w[0] + ' ' + w[1]) + "); }()");
    }

    public static void rootIntegrateAdaptive(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root integrate adaptive"));
        if (w.length < 3) {
            Handlers.usage(":root integrate adaptive <from> <to> <expression>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TF1 f(\"sphere_integrand\", \"" + Handlers.join(w, 2) + "\", " + Handlers.csv(w[0] + ' ' + w[1]) + "); ROOT::Math::WrappedTF1 wrapped(f); ROOT::Math::Integrator ig(wrapped); return ig.Integral(" + Handlers.csv(w[0] + ' ' + w[1]) + "); }()");
    }

    public static void rootInterpNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root interp new"));
        if (w.length < 3) {
            Handlers.usage(":root interp new <name> <x,y> <x,y> ...".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<ROOT::Math::Interpolator>(\"" + w[0] + "\", " + "[]{ double flat[] = {" + Handlers.csv(Handlers.join(w, 1)) + "}; const std::size_t n = sizeof(flat) / sizeof(flat[0]) / 2; std::vector<double> x(n), y(n); for (std::size_t i = 0; i < n; ++i) { x[i] = flat[2 * i]; y[i] = flat[2 * i + 1]; } auto *it = new ROOT::Math::Interpolator(x, y); return it; }()" + ", \"ROOT::Math::Interpolator\")");
    }

    public static void rootInterpEval(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root interp eval"));
        if (w.length < 2) {
            Handlers.usage(":root interp eval <name> <x>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::Interpolator>(\"" + w[0] + "\")" + "->Eval(" + w[1] + ")");
    }

    public static void rootInterpDerivative(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root interp derivative"));
        if (w.length < 2) {
            Handlers.usage(":root interp derivative <name> <x>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::Interpolator>(\"" + w[0] + "\")" + "->Deriv(" + w[1] + ")");
    }

    public static void rootInterpIntegral(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root interp integral"));
        if (w.length < 3) {
            Handlers.usage(":root interp integral <name> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<ROOT::Math::Interpolator>(\"" + w[0] + "\")" + "->Integ(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    // --- reading fits back, TF1 parameters, applying a TMVA model, canvas layout and output, geometry, RooFit datasets and results, tree caches and indices, the rest of RDataFrame, the host system, regular expressions, file internals and more of TMath ---

    public static void rootMathSort(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math sort"));
        if (w.length < 2) {
            Handlers.usage(":root math sort <v> <v> ...".trim());
            return;
        }
        Handlers.cling(c, "[]{ double v[] = {" + Handlers.csv(Handlers.join(w, 0)) + "}; const int n = " + w.length + "; std::vector<int> order(n); TMath::Sort(n, v, order.data(), false); std::string out; for (int i = 0; i < n; ++i) { if (!out.empty()) { out += \" \"; } out += std::to_string(v[order[i]]); } return out; }()");
    }

    public static void rootMathFactorial(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math factorial"));
        if (w.length < 1) {
            Handlers.usage(":root math factorial <n>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Factorial(" + Handlers.asInt(w[0], 0) + ")");
    }

    public static void rootMathFreq(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math freq"));
        if (w.length < 1) {
            Handlers.usage(":root math freq <x>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Freq(" + w[0] + ")");
    }

    public static void rootMathStudent(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math student"));
        if (w.length < 2) {
            Handlers.usage(":root math student <p> <ndf>".trim());
            return;
        }
        Handlers.cling(c, "TMath::StudentQuantile(" + w[0] + ", " + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootMathChisquare(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math chisquare"));
        if (w.length < 2) {
            Handlers.usage(":root math chisquare <p> <ndf>".trim());
            return;
        }
        Handlers.cling(c, "TMath::ChisquareQuantile(" + w[0] + ", " + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootMathBeta(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math beta"));
        if (w.length < 2) {
            Handlers.usage(":root math beta <a> <b>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Beta(" + Handlers.csv(Handlers.join(w, 0)) + ")");
    }

    public static void rootMathBessel(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math bessel"));
        if (w.length < 2) {
            Handlers.usage(":root math bessel <order> <x>".trim());
            return;
        }
        Handlers.cling(c, "TMath::BesselI(" + Handlers.asInt(w[0], 0) + ", " + w[1] + ")");
    }

    public static void rootMathComplex(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math complex"));
        if (w.length < 2) {
            Handlers.usage(":root math complex <real> <imaginary>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TComplex z(" + Handlers.csv(Handlers.join(w, 0)) + "); return std::string(\"modulus \") + std::to_string(z.Rho()) + \"  argument \" + std::to_string(z.Theta()); }()");
    }

}
