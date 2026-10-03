! sphere_spx.f90 -- Sphere Physics eXchange, the Fortran reader.
!
! Fortran 2008, no library. Sphere writes PDF sets, event samples and jets
! into SPX files; this reads them with stream access, one statement per array.
! The PDF interpolation is the same arithmetic, in the same order, as Sphere's
! Java one and LHAPDF's log-bicubic (the parentheses below pin the order the
! compiler would otherwise be free to change), so the answers agree to the bit:
! ":bridge crosscheck" shows it.
!
!   use sphere_spx
!   type(spx_pdf) :: ct
!   call spx_open_pdf(ct, 'CT18NLO')               ! $SPHERE_BRIDGE/CT18NLO.spx
!   g = spx_xfxq2(ct, 21, 1d-3, 1d4)                ! member 0
!   call spx_publish('pt', edges, counts)           ! shows up in Sphere's Plots tab
!
! Code written for LHAPDF's Fortran interface (InitPDFsetByName, InitPDF,
! evolvePDF, alphasPDF) needs no change at all: sphere_lhaglue.f90 provides
! those routines on top of this module, and Sphere links it in by itself.
!
! Arrays count from 0, as the Java and C++ versions do, so the three can be
! read side by side.

module sphere_spx
  use, intrinsic :: iso_fortran_env, only: int32, int64, real64
  implicit none
  private

  public :: spx_pdf, spx_events, spx_open_pdf, spx_open_events, spx_xfxq2, spx_xfxq, spx_alphasq2, &
            spx_alphasq, spx_inrange, spx_members, spx_publish, spx_bridge_folder, spx_resolve, &
            spx_crosscheck, spx_meta, spx_column

  integer, parameter :: F64 = 1, I64 = 2, TEXT = 3

  type :: spx_pdf
    logical :: loaded = .false.
    character(len=:), allocatable :: name, errortype, meta
    integer :: nf = 0, nq = 0, nx = 0, nm = 0, na = 0
    integer(int64), allocatable :: pids(:)
    real(real64), allocatable :: xs(:), q2s(:), logx(:), logq2(:), xf(:)
    real(real64), allocatable :: asq2(:), aslog(:), asval(:)
    integer, allocatable :: pieces(:)
    logical :: weighted = .false., ashold = .true.
    integer :: lookup(0:14) = -1
    real(real64) :: errcl = 68.268949_real64
  end type spx_pdf

  type :: spx_events
    logical :: loaded = .false.
    integer :: n = 0
    integer(int64), allocatable :: offset(:), pdg(:), jet_offset(:), jet_of(:)
    real(real64), allocatable :: p4(:), weight(:), incoming(:), jet_p4(:)
  end type spx_events

  type :: section
    character(len=24) :: name
    integer(int32) :: type
    integer(int64) :: offset, count
  end type section

contains

  ! ---------------------------------------------------------------------------
  ! Files
  ! ---------------------------------------------------------------------------

  function spx_bridge_folder() result(folder)
    character(len=:), allocatable :: folder
    integer :: length, status
    call get_environment_variable('SPHERE_BRIDGE', length=length, status=status)
    if (status /= 0 .or. length == 0) then
      folder = 'bridge'
    else
      allocate(character(len=length) :: folder)
      call get_environment_variable('SPHERE_BRIDGE', folder)
    end if
  end function spx_bridge_folder

  !> A name, or a path: a bare name is looked for in the bridge folder.
  function spx_resolve(name) result(path)
    character(len=*), intent(in) :: name
    character(len=:), allocatable :: path
    character(len=:), allocatable :: bare
    bare = trim(adjustl(name))
    if (index(bare, '/') > 0 .or. index(bare, '\') > 0) then
      path = bare
    else if (len(bare) > 4) then
      if (bare(len(bare)-3:) == '.spx') then
        path = spx_bridge_folder() // '/' // bare
      else
        path = spx_bridge_folder() // '/' // bare // '.spx'
      end if
    else
      path = spx_bridge_folder() // '/' // bare // '.spx'
    end if
  end function spx_resolve

  subroutine open_file(path, unit, kind, sections, ok)
    character(len=*), intent(in) :: path
    integer, intent(out) :: unit, kind
    type(section), allocatable, intent(out) :: sections(:)
    logical, intent(out) :: ok
    character(len=8) :: magic
    integer(int32) :: version, kind32, n, pad
    integer(int64) :: dir
    integer :: k, ios
    ok = .false.
    open(newunit=unit, file=path, access='stream', form='unformatted', status='old', action='read', iostat=ios)
    if (ios /= 0) then
      write(*, '(a)') 'sphere_spx: cannot open ' // trim(path)
      return
    end if
    read(unit, pos=1, iostat=ios) magic, version, kind32, n, pad, dir
    if (ios /= 0 .or. magic /= 'SPHRSPX1' .or. version /= 1) then
      write(*, '(a)') 'sphere_spx: ' // trim(path) // ' is not an SPX file'
      close(unit)
      return
    end if
    kind = kind32
    allocate(sections(0:n-1))
    do k = 0, n - 1
      read(unit, pos=dir + 64_int64 * k + 1) sections(k)%name, sections(k)%type, pad, sections(k)%offset, &
                                             sections(k)%count
    end do
    ok = .true.
  end subroutine open_file

  integer function find(sections, name)
    type(section), intent(in) :: sections(0:)
    character(len=*), intent(in) :: name
    integer :: k
    find = -1
    do k = 0, size(sections) - 1
      if (trim(sections(k)%name) == name) then
        find = k
        return
      end if
    end do
  end function find

  subroutine read_f64(unit, sections, name, values)
    integer, intent(in) :: unit
    type(section), intent(in) :: sections(0:)
    character(len=*), intent(in) :: name
    real(real64), allocatable, intent(out) :: values(:)
    integer :: k
    k = find(sections, name)
    if (k < 0) then
      allocate(values(0:-1))
      return
    end if
    allocate(values(0:sections(k)%count - 1))
    if (sections(k)%count > 0) read(unit, pos=sections(k)%offset + 1) values
  end subroutine read_f64

  subroutine read_i64(unit, sections, name, values)
    integer, intent(in) :: unit
    type(section), intent(in) :: sections(0:)
    character(len=*), intent(in) :: name
    integer(int64), allocatable, intent(out) :: values(:)
    integer :: k
    k = find(sections, name)
    if (k < 0) then
      allocate(values(0:-1))
      return
    end if
    allocate(values(0:sections(k)%count - 1))
    if (sections(k)%count > 0) read(unit, pos=sections(k)%offset + 1) values
  end subroutine read_i64

  function read_text(unit, sections, name) result(text)
    integer, intent(in) :: unit
    type(section), intent(in) :: sections(0:)
    character(len=*), intent(in) :: name
    character(len=:), allocatable :: text
    integer :: k
    k = find(sections, name)
    if (k < 0) then
      text = ''
      return
    end if
    allocate(character(len=sections(k)%count) :: text)
    if (sections(k)%count > 0) read(unit, pos=sections(k)%offset + 1) text
  end function read_text

  !> One column of a table Sphere wrote (':fjco bridge', ':fjco export x.spx'), by file
  !> name or path; int64 columns come as real64. ok is false when the file or column is missing.
  !>
  !>   real(real64), allocatable :: tau21(:)
  !>   call spx_column('fjco', 'tau21', tau21, ok)
  subroutine spx_column(name, column, values, ok)
    character(len=*), intent(in) :: name, column
    real(real64), allocatable, intent(out) :: values(:)
    logical, intent(out) :: ok
    type(section), allocatable :: sections(:)
    integer(int64), allocatable :: ints(:)
    integer :: unit, kind, k
    call open_file(spx_resolve(name), unit, kind, sections, ok)
    if (.not. ok) then
      allocate(values(0:-1))
      return
    end if
    k = find(sections, column)
    if (k < 0) then
      ok = .false.
      allocate(values(0:-1))
    else if (sections(k)%type == I64) then
      call read_i64(unit, sections, column, ints)
      allocate(values(0:size(ints) - 1))
      values = real(ints, real64)
    else
      call read_f64(unit, sections, column, values)
    end if
    close(unit)
  end subroutine spx_column

  !> The value of "Key: value" in a meta text, or the fallback.
  function spx_meta(meta, key, fallback) result(value)
    character(len=*), intent(in) :: meta, key, fallback
    character(len=:), allocatable :: value
    integer :: start, finish, colon
    value = fallback
    start = 1
    do while (start <= len(meta))
      finish = index(meta(start:), achar(10))
      if (finish == 0) then
        finish = len(meta)
      else
        finish = start + finish - 2
      end if
      colon = index(meta(start:finish), ':')
      if (colon > 0) then
        if (trim(adjustl(meta(start:start+colon-2))) == key) then
          value = trim(adjustl(meta(start+colon:finish)))
          return
        end if
      end if
      start = finish + 2
    end do
  end function spx_meta

  ! ---------------------------------------------------------------------------
  ! Parton distributions
  ! ---------------------------------------------------------------------------

  !> Reads a set Sphere exported, by name or by path.
  subroutine spx_open_pdf(pdf, name, ok)
    type(spx_pdf), intent(inout) :: pdf
    character(len=*), intent(in) :: name
    logical, intent(out), optional :: ok
    type(section), allocatable :: sections(:)
    integer :: unit, kind, k, n
    integer(int64), allocatable :: shape(:)
    logical :: fine
    real(real64) :: cl
    integer :: ios
    character(len=:), allocatable :: text
    if (present(ok)) ok = .false.
    call open_file(spx_resolve(name), unit, kind, sections, fine)
    if (.not. fine) return
    if (kind /= 1) then
      write(*, '(a)') 'sphere_spx: ' // trim(name) // ' does not hold a PDF set'
      close(unit)
      return
    end if
    pdf%meta = read_text(unit, sections, 'meta')
    call read_i64(unit, sections, 'shape', shape)
    pdf%nf = int(shape(0)); pdf%nq = int(shape(1)); pdf%nx = int(shape(2)); pdf%nm = int(shape(3))
    call read_i64(unit, sections, 'pids', pdf%pids)
    call read_f64(unit, sections, 'xknots', pdf%xs)
    call read_f64(unit, sections, 'q2knots', pdf%q2s)
    call read_f64(unit, sections, 'logx', pdf%logx)
    call read_f64(unit, sections, 'logq2', pdf%logq2)
    call read_f64(unit, sections, 'xf', pdf%xf)
    call read_f64(unit, sections, 'as_q2', pdf%asq2)
    call read_f64(unit, sections, 'as_logq2', pdf%aslog)
    call read_f64(unit, sections, 'as_val', pdf%asval)
    close(unit)
    pdf%na = size(pdf%asq2)
    pdf%name = spx_meta(pdf%meta, 'SetName', trim(name))
    pdf%errortype = spx_meta(pdf%meta, 'ErrorType', 'none')
    pdf%weighted = spx_meta(pdf%meta, 'Accuracy', 'lhapdf') == 'weighted'
    pdf%ashold = spx_meta(pdf%meta, 'AlphaS_Above', 'hold') == 'hold'
    text = spx_meta(pdf%meta, 'ErrorConfLevel', '68.268949')
    read(text, *, iostat=ios) cl
    if (ios == 0) pdf%errcl = cl
    pdf%lookup = -1
    do k = 0, pdf%nf - 1
      if (pdf%pids(k) >= -6 .and. pdf%pids(k) <= 6) pdf%lookup(pdf%pids(k) + 6) = k
      if (pdf%pids(k) == 21) pdf%lookup(13) = k
      if (pdf%pids(k) == 22) pdf%lookup(14) = k
    end do
    ! A repeated scale starts a new stretch of the alpha_s table: a threshold.
    if (allocated(pdf%pieces)) deallocate(pdf%pieces)
    n = 1
    do k = 1, pdf%na - 1
      if (abs(pdf%asq2(k) - pdf%asq2(k-1)) < 2.220446049250313e-16_real64) n = n + 1
    end do
    allocate(pdf%pieces(0:n-1))
    pdf%pieces(0) = 0
    n = 1
    do k = 1, pdf%na - 1
      if (abs(pdf%asq2(k) - pdf%asq2(k-1)) < 2.220446049250313e-16_real64) then
        pdf%pieces(n) = k
        n = n + 1
      end if
    end do
    pdf%loaded = .true.
    if (present(ok)) ok = .true.
  end subroutine spx_open_pdf

  integer function spx_members(pdf)
    type(spx_pdf), intent(in) :: pdf
    spx_members = pdf%nm
  end function spx_members

  integer function flavor(pdf, pid)
    type(spx_pdf), intent(in) :: pdf
    integer, intent(in) :: pid
    flavor = -1
    if (pid == 0 .or. pid == 21) then
      flavor = pdf%lookup(13)
    else if (pid == 22) then
      flavor = pdf%lookup(14)
    else if (pid >= -6 .and. pid <= 6) then
      flavor = pdf%lookup(pid + 6)
    end if
  end function flavor

  logical function spx_inrange(pdf, x, q2)
    type(spx_pdf), intent(in) :: pdf
    real(real64), intent(in) :: x, q2
    spx_inrange = x >= pdf%xs(0) .and. x <= pdf%xs(pdf%nx-1) .and. q2 >= pdf%q2s(0) .and. q2 <= pdf%q2s(pdf%nq-1)
  end function spx_inrange

  integer function below(value, knots, n)
    real(real64), intent(in) :: value
    real(real64), intent(in) :: knots(0:)
    integer, intent(in) :: n
    integer :: low, high, mid
    low = 0
    high = n
    do while (low < high)
      mid = (low + high) / 2
      if (knots(mid) <= value) then
        low = mid + 1
      else
        high = mid
      end if
    end do
    if (low >= n) low = n - 1
    if (low == 0) then
      below = 0
    else
      below = low - 1
    end if
  end function below

  real(real64) function at(pdf, base, ix, iq, f)
    type(spx_pdf), intent(in) :: pdf
    integer(int64), intent(in) :: base
    integer, intent(in) :: ix, iq, f
    at = pdf%xf(base + (int(ix, int64) * pdf%nq + iq) * pdf%nf + f)
  end function at

  real(real64) function slope(pdf, base, ix, iq, f)
    type(spx_pdf), intent(in) :: pdf
    integer(int64), intent(in) :: base
    integer, intent(in) :: ix, iq, f
    real(real64) :: back, ahead, left, right
    integer :: nx
    nx = pdf%nx
    if (ix /= 0 .and. ix /= nx - 1) then
      back = pdf%logx(ix) - pdf%logx(ix-1)
      ahead = pdf%logx(ix+1) - pdf%logx(ix)
      left = (at(pdf, base, ix, iq, f) - at(pdf, base, ix-1, iq, f)) / back
      right = (at(pdf, base, ix+1, iq, f) - at(pdf, base, ix, iq, f)) / ahead
      if (pdf%weighted) then
        slope = ((ahead * left) + (back * right)) / (back + ahead)
      else
        slope = (left + right) / 2.0_real64
      end if
    else if (ix == 0) then
      slope = (at(pdf, base, 1, iq, f) - at(pdf, base, 0, iq, f)) / (pdf%logx(1) - pdf%logx(0))
    else
      slope = (at(pdf, base, nx-1, iq, f) - at(pdf, base, nx-2, iq, f)) / (pdf%logx(nx-1) - pdf%logx(nx-2))
    end if
  end function slope

  real(real64) function alongx(pdf, base, ix, iq, f, t)
    type(spx_pdf), intent(in) :: pdf
    integer(int64), intent(in) :: base
    integer, intent(in) :: ix, iq, f
    real(real64), intent(in) :: t
    real(real64) :: dlogx, vl, vh, vdl, vdh, c0, c1, c2, c3, t2
    dlogx = pdf%logx(ix+1) - pdf%logx(ix)
    vl = at(pdf, base, ix, iq, f)
    vh = at(pdf, base, ix+1, iq, f)
    vdl = slope(pdf, base, ix, iq, f) * dlogx
    vdh = slope(pdf, base, ix+1, iq, f) * dlogx
    c0 = ((vdh + vdl) - 2.0_real64 * vh) + 2.0_real64 * vl
    c1 = (((3.0_real64 * vh) - 3.0_real64 * vl) - 2.0_real64 * vdl) - vdh
    c2 = vdl
    c3 = vl
    t2 = t * t
    alongx = (((c0 * t2) * t + c1 * t2) + c2 * t) + c3
  end function alongx

  real(real64) function hermite(t, vl, vdl, vh, vdh)
    real(real64), intent(in) :: t, vl, vdl, vh, vdh
    real(real64) :: t2, t3
    t2 = t * t
    t3 = t * t2
    hermite = ((((2.0_real64 * t3 - 3.0_real64 * t2) + 1.0_real64) * vl &
             + ((t3 - 2.0_real64 * t2) + t) * vdl) &
             + ((-2.0_real64) * t3 + 3.0_real64 * t2) * vh) &
             + (t3 - t2) * vdh
  end function hermite

  real(real64) function linear(x, xl, xh, yl, yh)
    real(real64), intent(in) :: x, xl, xh, yl, yh
    linear = yl + ((x - xl) / (xh - xl)) * (yh - yl)
  end function linear

  recursive real(real64) function interpolate(pdf, base, f, x, q2) result(value)
    type(spx_pdf), intent(in) :: pdf
    integer(int64), intent(in) :: base
    integer, intent(in) :: f
    real(real64), intent(in) :: x, q2
    integer :: ix, iq, nq
    real(real64) :: lx, lq, dlogx, tx, dlogq1, tq, fl, fh, vl, vh, vdl, vdh, vll, vhh, dlogq0, dlogq2
    logical :: lowseam, highseam
    nq = pdf%nq
    ix = below(x, pdf%xs, pdf%nx)
    iq = below(q2, pdf%q2s, nq)
    lx = log(x)
    lq = log(q2)
    lowseam = (iq == 0)
    if (.not. lowseam) lowseam = pdf%q2s(iq) == pdf%q2s(iq-1)
    highseam = (iq + 1 == nq - 1)
    if (.not. highseam) highseam = pdf%q2s(iq+1) == pdf%q2s(iq+2)
    dlogx = pdf%logx(ix+1) - pdf%logx(ix)
    tx = (lx - pdf%logx(ix)) / dlogx
    dlogq1 = pdf%logq2(iq+1) - pdf%logq2(iq)
    tq = (lq - pdf%logq2(iq)) / dlogq1
    if (lowseam .and. highseam) then
      fl = linear(lx, pdf%logx(ix), pdf%logx(ix+1), at(pdf, base, ix, iq, f), at(pdf, base, ix+1, iq, f))
      fh = linear(lx, pdf%logx(ix), pdf%logx(ix+1), at(pdf, base, ix, iq+1, f), at(pdf, base, ix+1, iq+1, f))
      value = linear(lq, pdf%logq2(iq), pdf%logq2(iq+1), fl, fh)
      return
    end if
    vl = alongx(pdf, base, ix, iq, f, tx)
    vh = alongx(pdf, base, ix, iq+1, f, tx)
    if (lowseam) then
      vdl = vh - vl
      vhh = alongx(pdf, base, ix, iq+2, f, tx)
      dlogq2 = 1.0_real64 / (pdf%logq2(iq+2) - pdf%logq2(iq+1))
      vdh = (vdl + ((vhh - vh) * dlogq1) * dlogq2) * 0.5_real64
    else if (highseam) then
      vdh = vh - vl
      vll = alongx(pdf, base, ix, iq-1, f, tx)
      dlogq0 = 1.0_real64 / (pdf%logq2(iq) - pdf%logq2(iq-1))
      vdl = (vdh + ((vl - vll) * dlogq1) * dlogq0) * 0.5_real64
    else
      vll = alongx(pdf, base, ix, iq-1, f, tx)
      dlogq0 = 1.0_real64 / (pdf%logq2(iq) - pdf%logq2(iq-1))
      vdl = ((vh - vl) + ((vl - vll) * dlogq1) * dlogq0) * 0.5_real64
      vhh = alongx(pdf, base, ix, iq+2, f, tx)
      dlogq2 = 1.0_real64 / (pdf%logq2(iq+2) - pdf%logq2(iq+1))
      vdh = ((vh - vl) + ((vhh - vh) * dlogq1) * dlogq2) * 0.5_real64
    end if
    value = hermite(tq, vl, vdl, vh, vdh)
  end function interpolate

  real(real64) function alonglog(x, xa, xb, fa, fb)
    real(real64), intent(in) :: x, xa, xb, fa, fb
    real(real64) :: t
    t = (log(x) - log(xa)) / (log(xb) - log(xa))
    if (fa > 1d-3 .and. fb > 1d-3) then
      alonglog = exp(log(fa) + t * (log(fb) - log(fa)))
    else
      alonglog = fa + t * (fb - fa)
    end if
  end function alonglog

  !> LHAPDF's continuation outside the grid (the MSTW rule below Q min).
  real(real64) function continuation(pdf, base, f, x, q2) result(value)
    type(spx_pdf), intent(in) :: pdf
    integer(int64), intent(in) :: base
    integer, intent(in) :: f
    real(real64), intent(in) :: x, q2
    real(real64) :: xmin, xmin1, xmax, q2min, q2max1, q2max, atmin, atmin1, atedge, justabove, anomalous, ratio
    xmin = pdf%xs(0); xmin1 = pdf%xs(1); xmax = pdf%xs(pdf%nx-1)
    q2min = pdf%q2s(0); q2max1 = pdf%q2s(pdf%nq-2); q2max = pdf%q2s(pdf%nq-1)
    if (x > xmax) then
      value = 0.0_real64      ! LHAPDF refuses; a momentum fraction above one carries nothing.
    else if (x < xmin .and. q2 >= q2min .and. q2 <= q2max) then
      value = alonglog(x, xmin, xmin1, interpolate(pdf, base, f, xmin, q2), interpolate(pdf, base, f, xmin1, q2))
    else if (x >= xmin .and. q2 > q2max) then
      value = alonglog(q2, q2max, q2max1, interpolate(pdf, base, f, x, q2max), interpolate(pdf, base, f, x, q2max1))
    else if (x < xmin .and. q2 > q2max) then
      atmin = alonglog(q2, q2max, q2max1, interpolate(pdf, base, f, xmin, q2max), &
                       interpolate(pdf, base, f, xmin, q2max1))
      atmin1 = alonglog(q2, q2max, q2max1, interpolate(pdf, base, f, xmin1, q2max), &
                        interpolate(pdf, base, f, xmin1, q2max1))
      value = alonglog(x, xmin, xmin1, atmin, atmin1)
    else if (q2 < q2min) then
      if (x < xmin) then
        atedge = alonglog(x, xmin, xmin1, interpolate(pdf, base, f, xmin, q2min), &
                          interpolate(pdf, base, f, xmin1, q2min))
        justabove = alonglog(x, xmin, xmin1, interpolate(pdf, base, f, xmin, 1.01_real64 * q2min), &
                             interpolate(pdf, base, f, xmin1, 1.01_real64 * q2min))
      else
        atedge = interpolate(pdf, base, f, x, q2min)
        justabove = interpolate(pdf, base, f, x, 1.01_real64 * q2min)
      end if
      if (abs(atedge) >= 1d-5) then
        anomalous = max(-2.5_real64, ((justabove - atedge) / atedge) / 0.01_real64)
      else
        anomalous = 1.0_real64
      end if
      ratio = q2 / q2min
      value = atedge * ratio ** (((anomalous * ratio) + 1.0_real64) - ratio)
    else
      value = interpolate(pdf, base, f, x, q2)
    end if
  end function continuation

  !> x f(x, Q^2) of one member (0 is the central one); 0 for a flavour the set does not carry.
  real(real64) function spx_xfxq2(pdf, pid, x, q2, member) result(value)
    type(spx_pdf), intent(in) :: pdf
    integer, intent(in) :: pid
    real(real64), intent(in) :: x, q2
    integer, intent(in), optional :: member
    integer :: f, m
    integer(int64) :: base
    value = 0.0_real64
    m = 0
    if (present(member)) m = member
    if (.not. pdf%loaded) return
    f = flavor(pdf, pid)
    if (f < 0 .or. m < 0 .or. m >= pdf%nm) return
    if (x <= 0.0_real64 .or. q2 <= 0.0_real64) return
    base = int(m, int64) * pdf%nx * pdf%nq * pdf%nf
    if (spx_inrange(pdf, x, q2)) then
      value = interpolate(pdf, base, f, x, q2)
    else
      value = continuation(pdf, base, f, x, q2)
    end if
  end function spx_xfxq2

  real(real64) function spx_xfxq(pdf, pid, x, q, member)
    type(spx_pdf), intent(in) :: pdf
    integer, intent(in) :: pid
    real(real64), intent(in) :: x, q
    integer, intent(in), optional :: member
    if (present(member)) then
      spx_xfxq = spx_xfxq2(pdf, pid, x, q * q, member)
    else
      spx_xfxq = spx_xfxq2(pdf, pid, x, q * q)
    end if
  end function spx_xfxq

  !> alpha_s(Q^2) from the table the set carries, interpolated the LHAPDF way.
  real(real64) function spx_alphasq2(pdf, q2) result(value)
    type(spx_pdf), intent(in) :: pdf
    real(real64), intent(in) :: q2
    integer :: n, next, pc, s, length, i
    real(real64) :: sl, sh, dlog, t, lo, hi, slope_
    n = pdf%na
    if (n == 0 .or. q2 < 0.0_real64) then
      value = ieee_nan()
      return
    end if
    associate (q => pdf%asq2, a => pdf%asval, l => pdf%aslog)
      if (q2 < q(0)) then
        next = 1
        do while (next < n)
          if (q(0) /= q(next)) exit
          next = next + 1
        end do
        if (next >= n) then
          value = a(0)
        else if (a(0) <= 0.0_real64 .or. a(next) <= 0.0_real64) then
          value = a(0)
        else
          slope_ = log10(a(next) / a(0)) / log10(q(next) / q(0))
          value = a(0) * (q2 / q(0)) ** slope_
        end if
        return
      end if
      if (q2 > q(n-1)) then
        value = a(n-1)
        if (pdf%ashold .or. n < 3) return
        lo = a(n-2)
        hi = a(n-1)
        if (lo <= 0.0_real64 .or. hi <= 0.0_real64 .or. q(n-1) == q(n-2)) return
        slope_ = log(hi / lo) / log(q(n-1) / q(n-2))
        value = hi * (q2 / q(n-1)) ** slope_
        return
      end if
      pc = 0
      do while (pc + 1 < size(pdf%pieces))
        if (q(pdf%pieces(pc+1)) > q2) exit
        pc = pc + 1
      end do
      s = pdf%pieces(pc)
      if (pc + 1 < size(pdf%pieces)) then
        length = pdf%pieces(pc+1) - s
      else
        length = n - s
      end if
      i = below(q2, q(s:s+length-1), length)
      if (length == 2) then
        sl = forward(0)
        sh = sl
      else if (i == 0) then
        sl = forward(i)
        sh = central(i + 1)
      else if (i == length - 2) then
        sl = central(i)
        sh = backward(i + 1)
      else
        sl = central(i)
        sh = central(i + 1)
      end if
      dlog = l(s+i+1) - l(s+i)
      t = (log(q2) - l(s+i)) / dlog
      value = hermite(t, a(s+i), sl * dlog, a(s+i+1), sh * dlog)
      if (abs(value) >= 2.0_real64) value = huge(1.0_real64)
    end associate
  contains
    real(real64) function forward(k)
      integer, intent(in) :: k
      forward = (pdf%asval(s+k+1) - pdf%asval(s+k)) / (pdf%aslog(s+k+1) - pdf%aslog(s+k))
    end function forward
    real(real64) function backward(k)
      integer, intent(in) :: k
      backward = (pdf%asval(s+k) - pdf%asval(s+k-1)) / (pdf%aslog(s+k) - pdf%aslog(s+k-1))
    end function backward
    real(real64) function central(k)
      integer, intent(in) :: k
      central = 0.5_real64 * (forward(k) + backward(k))
    end function central
  end function spx_alphasq2

  real(real64) function spx_alphasq(pdf, q)
    type(spx_pdf), intent(in) :: pdf
    real(real64), intent(in) :: q
    spx_alphasq = spx_alphasq2(pdf, q * q)
  end function spx_alphasq

  real(real64) function ieee_nan()
    use, intrinsic :: ieee_arithmetic, only: ieee_value, ieee_quiet_nan
    ieee_nan = ieee_value(1.0_real64, ieee_quiet_nan)
  end function ieee_nan

  ! ---------------------------------------------------------------------------
  ! Events and jets
  ! ---------------------------------------------------------------------------

  !> Reads an event sample: offsets, p4 (px,py,pz,E per particle), pdg, weight, and the jets if any.
  subroutine spx_open_events(ev, name, ok)
    type(spx_events), intent(inout) :: ev
    character(len=*), intent(in) :: name
    logical, intent(out), optional :: ok
    type(section), allocatable :: sections(:)
    integer :: unit, kind
    logical :: fine
    if (present(ok)) ok = .false.
    call open_file(spx_resolve(name), unit, kind, sections, fine)
    if (.not. fine) return
    if (kind /= 2) then
      close(unit)
      return
    end if
    call read_i64(unit, sections, 'evt_offset', ev%offset)
    call read_f64(unit, sections, 'p4', ev%p4)
    call read_i64(unit, sections, 'pdg', ev%pdg)
    call read_f64(unit, sections, 'weight', ev%weight)
    call read_f64(unit, sections, 'incoming', ev%incoming)
    call read_i64(unit, sections, 'jet_offset', ev%jet_offset)
    call read_f64(unit, sections, 'jet_p4', ev%jet_p4)
    call read_i64(unit, sections, 'jet_of', ev%jet_of)
    close(unit)
    ev%n = size(ev%offset) - 1
    ev%loaded = .true.
    if (present(ok)) ok = .true.
  end subroutine spx_open_events

  ! ---------------------------------------------------------------------------
  ! Writing results back
  ! ---------------------------------------------------------------------------

  subroutine write_table(path, title, meta, names, columns, lengths)
    character(len=*), intent(in) :: path, title, meta
    character(len=24), intent(in) :: names(:)
    real(real64), intent(in) :: columns(:, :)       ! (longest column, number of columns)
    integer, intent(in) :: lengths(:)
    integer :: unit, k, n, ios
    integer(int64) :: offsets(0:size(names)), at, size_
    character(len=24) :: padded
    character(len=:), allocatable :: text
    character(len=64) :: zeros
    zeros = repeat(achar(0), 64)
    text = 'Title: ' // trim(title) // achar(10) // meta
    n = size(names) + 1
    at = aligned(64_int64 + 64_int64 * n)
    offsets(0) = at
    at = aligned(at + len(text))
    do k = 1, size(names)
      offsets(k) = at
      at = aligned(at + 8_int64 * lengths(k))
    end do
    size_ = at
    ! Written in place: Fortran has no standard rename. The header states the
    ! final size first, and Sphere reads a file only once it has reached it.
    open(newunit=unit, file=path, access='stream', form='unformatted', status='replace', &
         action='write', iostat=ios)
    if (ios /= 0) then
      write(*, '(a)') 'sphere_spx: cannot write ' // path
      return
    end if
    padded = title
    write(unit) 'SPHRSPX1', 1_int32, 3_int32, int(n, int32), 0_int32, 64_int64, size_, padded
    padded = 'meta'
    write(unit) padded, 3_int32, 0_int32, offsets(0), int(len(text), int64), zeros(1:16)
    do k = 1, size(names)
      write(unit) names(k), 1_int32, 0_int32, offsets(k), int(lengths(k), int64), zeros(1:16)
    end do
    call pad_to(offsets(0))
    write(unit) text
    do k = 1, size(names)
      call pad_to(offsets(k))
      if (lengths(k) > 0) write(unit) columns(1:lengths(k), k)
    end do
    call pad_to(size_)
    close(unit)
  contains
    subroutine pad_to(target)
      integer(int64), intent(in) :: target
      integer(int64) :: here
      inquire(unit, pos=here)
      do while (here - 1 < target)
        write(unit) zeros(1:int(min(64_int64, target - here + 1)))
        inquire(unit, pos=here)
      end do
    end subroutine pad_to
  end subroutine write_table

  pure integer(int64) function aligned(v)
    integer(int64), intent(in) :: v
    aligned = (v + 63_int64) / 64_int64 * 64_int64
  end function aligned

  !> Hands a histogram to Sphere: it appears in the Plots tab by itself.
  subroutine spx_publish(name, edges, values, err_plus, err_minus, xlabel)
    character(len=*), intent(in) :: name
    real(real64), intent(in) :: edges(:), values(:)
    real(real64), intent(in), optional :: err_plus(:), err_minus(:)
    character(len=*), intent(in), optional :: xlabel
    character(len=24), allocatable :: names(:)
    real(real64), allocatable :: columns(:, :)
    integer, allocatable :: lengths(:)
    integer :: n
    character(len=:), allocatable :: meta, label
    n = 2
    if (present(err_plus)) n = n + 1
    if (present(err_minus)) n = n + 1
    allocate(names(n), lengths(n), columns(size(edges), n))
    columns = 0
    names(1) = 'edges';  lengths(1) = size(edges);  columns(1:size(edges), 1) = edges
    names(2) = 'values'; lengths(2) = size(values); columns(1:size(values), 2) = values
    n = 2
    if (present(err_plus)) then
      n = n + 1
      names(n) = 'err_plus'; lengths(n) = size(err_plus); columns(1:size(err_plus), n) = err_plus
    end if
    if (present(err_minus)) then
      n = n + 1
      names(n) = 'err_minus'; lengths(n) = size(err_minus); columns(1:size(err_minus), n) = err_minus
    end if
    label = trim(name)
    if (present(xlabel)) label = trim(xlabel)
    meta = 'Kind: histogram' // achar(10) // 'XLabel: ' // label // achar(10) // 'Engine: Fortran' // achar(10)
    call execute_command_line_mkdir(spx_bridge_folder() // '/outbox')
    call write_table(spx_bridge_folder() // '/outbox/' // trim(name) // '.spx', trim(name), meta, names, columns, &
                     lengths)
  end subroutine spx_publish

  subroutine execute_command_line_mkdir(folder)
    character(len=*), intent(in) :: folder
    logical :: there
    inquire(file=folder // '/.', exist=there)
    if (.not. there) call execute_command_line('mkdir "' // folder // '"', wait=.true.)
  end subroutine execute_command_line_mkdir

  ! ---------------------------------------------------------------------------
  ! The cross-check Sphere runs on every engine
  ! ---------------------------------------------------------------------------

  subroutine spx_crosscheck(pdfpath, pointspath, outpath)
    character(len=*), intent(in) :: pdfpath, pointspath, outpath
    type(spx_pdf) :: pdf
    type(section), allocatable :: sections(:)
    real(real64), allocatable :: pid(:), x(:), q2(:), member(:), aq2(:), xf(:), as(:), columns(:, :)
    character(len=24) :: names(3)
    integer :: unit, kind, k, n, na, rounds
    integer(int64) :: start, now, rate
    real(real64) :: sink, elapsed
    logical :: fine
    character(len=32) :: text
    call spx_open_pdf(pdf, pdfpath, fine)
    if (.not. fine) stop 2
    call open_file(pointspath, unit, kind, sections, fine)
    if (.not. fine) stop 2
    call read_f64(unit, sections, 'pid', pid)
    call read_f64(unit, sections, 'x', x)
    call read_f64(unit, sections, 'q2', q2)
    call read_f64(unit, sections, 'member', member)
    call read_f64(unit, sections, 'as_q2', aq2)
    close(unit)
    n = size(pid)
    na = size(aq2)
    allocate(xf(0:n-1), as(0:na-1))
    do k = 0, n - 1
      xf(k) = spx_xfxq2(pdf, nint(pid(k)), x(k), q2(k), nint(member(k)))
    end do
    do k = 0, na - 1
      as(k) = spx_alphasq2(pdf, aq2(k))
    end do
    rounds = 0
    sink = 0
    call system_clock(start, rate)
    do
      do k = 0, n - 1
        sink = sink + spx_xfxq2(pdf, nint(pid(k)), x(k), q2(k), nint(member(k)))
      end do
      rounds = rounds + 1
      call system_clock(now)
      if (real(now - start, real64) / rate > 0.2_real64 .or. rounds >= 1000) exit
    end do
    elapsed = real(now - start, real64) / rate * 1d9 / (real(n, real64) * rounds)
    allocate(columns(max(n, na, 1), 3))
    columns = 0
    names(1) = 'xf'; names(2) = 'as'; names(3) = 'ns_per_eval'
    if (n > 0) columns(1:n, 1) = xf
    if (na > 0) columns(1:na, 2) = as
    columns(1, 3) = elapsed
    write(text, '(i0)') rounds
    call write_table(outpath, 'crosscheck', 'Engine: Fortran' // achar(10) // 'Rounds: ' // trim(text) // &
                     achar(10), names, columns, [n, na, 1])
    if (sink /= sink) write(*, *) 'NaN seen'
  end subroutine spx_crosscheck

end module sphere_spx
