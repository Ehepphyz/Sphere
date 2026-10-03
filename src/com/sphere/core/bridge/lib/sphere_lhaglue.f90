! sphere_lhaglue.f90 -- LHAPDF's Fortran interface, on Sphere's bridge.
!
! Decades of Fortran physics call LHAPDF through these routines:
!
!   call InitPDFsetByName('CT18NLO')      ! or 'cteq6l1.LHpdf', the LHAPDF 5 spelling
!   call InitPDF(0)
!   call evolvePDF(x, Q, f)               ! f(-6:6), x times the density, gluon at 0
!   as = alphasPDF(Q)
!
! and LHAPDF itself is the hard part to have on Windows. These are the same
! routines, as external procedures with the same names, reading the set from
! the SPX file Sphere exports; the numbers are those of Sphere's Java reader,
! which reproduces LHAPDF's interpolation. Sphere compiles and links this file
! by itself when a program calls them, and exports every set the program
! names in a literal.
!
! Up to ten sets at once, through the ...M variants, as in LHAPDF.

module sphere_lhaglue_state
  use sphere_spx
  implicit none
  integer, parameter :: max_sets = 10
  type(spx_pdf), save :: sets(max_sets)
  integer, save :: current(max_sets) = 0
contains
  !> The set name without the LHAPDF 5 suffix or the folder.
  function bare_name(name) result(out)
    character(len=*), intent(in) :: name
    character(len=:), allocatable :: out
    integer :: k
    out = trim(adjustl(name))
    k = max(index(out, '/', back=.true.), index(out, '\', back=.true.))
    if (k > 0) out = out(k+1:)
    k = index(out, '.LHgrid')
    if (k == 0) k = index(out, '.LHpdf')
    if (k == 0) k = index(out, '.spx')
    if (k > 0) out = out(:k-1)
  end function bare_name

  logical function usable(nset)
    integer, intent(in) :: nset
    usable = nset >= 1 .and. nset <= max_sets
    if (usable) usable = sets(nset)%loaded
    if (.not. usable) write(*, '(a,i0,a)') 'sphere_lhaglue: set ', nset, &
      ' is not initialised; call InitPDFsetByName first.'
  end function usable
end module sphere_lhaglue_state

subroutine InitPDFsetByNameM(nset, setname)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset
  character(len=*), intent(in) :: setname
  logical :: ok
  if (nset < 1 .or. nset > max_sets) stop 'sphere_lhaglue: nset out of range'
  call spx_open_pdf(sets(nset), bare_name(setname), ok)
  if (.not. ok) then
    write(*, '(a)') 'sphere_lhaglue: no exported set ' // bare_name(setname) // &
      '. In Sphere, ":lpdf install" then ":bridge pdf" it, or name it in a literal so Sphere exports it.'
    stop 1
  end if
  current(nset) = 0
end subroutine InitPDFsetByNameM

subroutine InitPDFsetByName(setname)
  implicit none
  character(len=*), intent(in) :: setname
  call InitPDFsetByNameM(1, setname)
end subroutine InitPDFsetByName

subroutine InitPDFsetM(nset, path)
  implicit none
  integer, intent(in) :: nset
  character(len=*), intent(in) :: path
  call InitPDFsetByNameM(nset, path)
end subroutine InitPDFsetM

subroutine InitPDFset(path)
  implicit none
  character(len=*), intent(in) :: path
  call InitPDFsetByNameM(1, path)
end subroutine InitPDFset

subroutine InitPDFM(nset, member)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset, member
  if (.not. usable(nset)) return
  if (member < 0 .or. member >= sets(nset)%nm) then
    write(*, '(a,i0,a,i0,a)') 'sphere_lhaglue: member ', member, ' does not exist (the set has ', sets(nset)%nm, ').'
    stop 1
  end if
  current(nset) = member
end subroutine InitPDFM

subroutine InitPDF(member)
  implicit none
  integer, intent(in) :: member
  call InitPDFM(1, member)
end subroutine InitPDF

subroutine evolvePDFM(nset, x, q, f)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset
  double precision, intent(in) :: x, q
  double precision, intent(out) :: f(-6:6)
  integer :: p
  f = 0
  if (.not. usable(nset)) return
  do p = -6, 6
    f(p) = spx_xfxq2(sets(nset), p, x, q * q, current(nset))
  end do
end subroutine evolvePDFM

subroutine evolvePDF(x, q, f)
  implicit none
  double precision, intent(in) :: x, q
  double precision, intent(out) :: f(-6:6)
  call evolvePDFM(1, x, q, f)
end subroutine evolvePDF

subroutine evolvePDFphotonM(nset, x, q, f, photon)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset
  double precision, intent(in) :: x, q
  double precision, intent(out) :: f(-6:6), photon
  call evolvePDFM(nset, x, q, f)
  photon = 0
  if (usable(nset)) photon = spx_xfxq2(sets(nset), 22, x, q * q, current(nset))
end subroutine evolvePDFphotonM

subroutine evolvePDFphoton(x, q, f, photon)
  implicit none
  double precision, intent(in) :: x, q
  double precision, intent(out) :: f(-6:6), photon
  call evolvePDFphotonM(1, x, q, f, photon)
end subroutine evolvePDFphoton

double precision function alphasPDFM(nset, q)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset
  double precision, intent(in) :: q
  alphasPDFM = 0
  if (usable(nset)) alphasPDFM = spx_alphasq2(sets(nset), q * q)
end function alphasPDFM

double precision function alphasPDF(q)
  implicit none
  double precision, intent(in) :: q
  double precision, external :: alphasPDFM
  alphasPDF = alphasPDFM(1, q)
end function alphasPDF

!> LHAPDF 5 counted the error members only: the central one is not included.
subroutine numberPDFM(nset, n)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset
  integer, intent(out) :: n
  n = 0
  if (usable(nset)) n = sets(nset)%nm - 1
end subroutine numberPDFM

subroutine numberPDF(n)
  implicit none
  integer, intent(out) :: n
  call numberPDFM(1, n)
end subroutine numberPDF

subroutine getXminM(nset, member, xmin)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset, member
  double precision, intent(out) :: xmin
  xmin = 0
  if (usable(nset)) xmin = sets(nset)%xs(0)
end subroutine getXminM

subroutine getXmin(member, xmin)
  implicit none
  integer, intent(in) :: member
  double precision, intent(out) :: xmin
  call getXminM(1, member, xmin)
end subroutine getXmin

subroutine getXmaxM(nset, member, xmax)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset, member
  double precision, intent(out) :: xmax
  xmax = 0
  if (usable(nset)) xmax = sets(nset)%xs(sets(nset)%nx - 1)
end subroutine getXmaxM

subroutine getXmax(member, xmax)
  implicit none
  integer, intent(in) :: member
  double precision, intent(out) :: xmax
  call getXmaxM(1, member, xmax)
end subroutine getXmax

subroutine getQ2minM(nset, member, q2min)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset, member
  double precision, intent(out) :: q2min
  q2min = 0
  if (usable(nset)) q2min = sets(nset)%q2s(0)
end subroutine getQ2minM

subroutine getQ2min(member, q2min)
  implicit none
  integer, intent(in) :: member
  double precision, intent(out) :: q2min
  call getQ2minM(1, member, q2min)
end subroutine getQ2min

subroutine getQ2maxM(nset, member, q2max)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset, member
  double precision, intent(out) :: q2max
  q2max = 0
  if (usable(nset)) q2max = sets(nset)%q2s(sets(nset)%nq - 1)
end subroutine getQ2maxM

subroutine getQ2max(member, q2max)
  implicit none
  integer, intent(in) :: member
  double precision, intent(out) :: q2max
  call getQ2maxM(1, member, q2max)
end subroutine getQ2max

subroutine getDescriptionM(nset)
  use sphere_lhaglue_state
  implicit none
  integer, intent(in) :: nset
  if (usable(nset)) write(*, '(a)') sets(nset)%name // ': ' // spx_meta(sets(nset)%meta, 'SetDesc', '')
end subroutine getDescriptionM

subroutine getDescription()
  implicit none
  call getDescriptionM(1)
end subroutine getDescription
