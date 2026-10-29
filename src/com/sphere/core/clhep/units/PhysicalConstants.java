package com.sphere.core.clhep.units;

/**
 * CLHEP's physical constants (Units/PhysicalConstants.h) in the units of {@link SystemOfUnits},
 * which it extends, as in C++ where the constants header includes the units: CODATA 2018 for
 * c, h, e, N_A and k, the particle masses CLHEP keeps.
 */
public final class PhysicalConstants extends SystemOfUnits {

    public static final double Avogadro = 6.02214076e+23/mole;
    public static final double c_light = 2.99792458e+8 * m/s;
    public static final double c_squared = c_light * c_light;
    public static final double h_Planck = 6.62607015e-34 * joule*s;
    public static final double hbar_Planck = h_Planck/twopi;
    public static final double hbarc = hbar_Planck * c_light;
    public static final double hbarc_squared = hbarc * hbarc;
    public static final double electron_charge = - eplus;   // see SystemOfUnits.h
    public static final double e_squared = eplus * eplus;
    public static final double electron_mass_c2 = 0.510998910 * MeV;
    public static final double proton_mass_c2 = 938.272013 * MeV;
    public static final double neutron_mass_c2 = 939.56536 * MeV;
    public static final double amu_c2 = 931.494028 * MeV;
    public static final double amu = amu_c2/c_squared;
    public static final double mu0 = 4*pi*1.e-7 * henry/m;
    public static final double epsilon0 = 1./(c_squared*mu0);
    public static final double elm_coupling = e_squared/(4*pi*epsilon0);
    public static final double fine_structure_const = elm_coupling/hbarc;
    public static final double classic_electr_radius = elm_coupling/electron_mass_c2;
    public static final double electron_Compton_length = hbarc/electron_mass_c2;
    public static final double Bohr_radius = electron_Compton_length/fine_structure_const;
    public static final double alpha_rcl2 = fine_structure_const *classic_electr_radius *classic_electr_radius;
    public static final double twopi_mc2_rcl2 = twopi*electron_mass_c2 *classic_electr_radius *classic_electr_radius;
    public static final double Bohr_magneton = (eplus*hbarc*c_light)/(2*electron_mass_c2);
    public static final double nuclear_magneton = (eplus*hbarc*c_light)/(2*proton_mass_c2);
    public static final double k_Boltzmann = 8.617333e-11 * MeV/kelvin;
    public static final double STP_Temperature = 273.15*kelvin;
    public static final double STP_Pressure = 1.*atmosphere;
    public static final double kGasThreshold = 10.*mg/cm3;
    public static final double universe_mean_density = 1.e-25*g/cm3;

    private PhysicalConstants() {
    }
}
