! sphere_mlink.f90
!
! TensorFlow, PyTorch, Keras, JAX and pyhf from Fortran, through the gateway (sphere_mlink.py says how), started if it
! is not running. Sphere compiles and links it with the bridge; elsewhere compile it after sphere_spx.f90.
!
!   use sphere_mlink
!   type(mlink_info) :: found
!   real(real64) :: norm, cls, limit
!   real(real64), allocatable :: y(:,:)
!   logical :: ok
!
!   call mlink_detect(found)                                       ! starts nothing
!   if (found%has_torch) print *, 'PyTorch ', found%torch
!
!   call mlink_put('x', x)                                         ! x(n, m): x(i, j) is x[i-1, j-1] in Python
!   call mlink_eval('torch.linalg.norm(torch.as_tensor(x)).item()', norm, ok)
!   call mlink_put('x', x)
!   call mlink_eval('tf.nn.softmax(tf.constant(x)).numpy()', y, ok)
!
!   call mlink_pyhf(signal, background, uncertainty, observed, cls, ok, upper_limit=limit)
!
!   if (.not. ok) print *, mlink_error()
!
! Each mlink_put is for the next call. Code runs in one namespace that outlives the calls: a model made by one call is
! there for the next. Written for gfortran (it uses its GETPID, and usleep from the C library).

module sphere_mlink
  use, intrinsic :: iso_fortran_env, only: int64, real64
  use, intrinsic :: iso_c_binding, only: c_int
  use sphere_spx, only: spx_write_table, spx_read_meta, spx_column, spx_meta, spx_bridge_folder
  implicit none
  private

  public :: mlink_info, mlink_detect, mlink_put, mlink_eval, mlink_exec, mlink_pyhf, mlink_predict, mlink_error, mlink_stop, &
            mlink_crosscheck

  type :: mlink_info
    logical :: found = .false., has_torch = .false., has_tensorflow = .false.
    logical :: has_keras = .false., has_jax = .false., has_pyhf = .false.
    character(len=:), allocatable :: python, torch, tensorflow, keras, jax, pyhf
  end type mlink_info

  interface mlink_put
    module procedure put_vector, put_matrix
  end interface

  interface mlink_eval
    module procedure eval_number, eval_vector, eval_matrix
  end interface

  interface mlink_predict
    module procedure predict_vector, predict_matrix
  end interface

  interface
    function c_usleep(microseconds) bind(C, name='usleep') result(status)
      import :: c_int
      integer(c_int), value :: microseconds
      integer(c_int) :: status
    end function c_usleep
  end interface

  integer, parameter :: most = 32
  character(len=24) :: put_names(most)
  character(len=64) :: put_shapes(most)
  integer :: put_lengths(most) = 0, put_count = 0
  real(real64), allocatable :: put_data(:)

  character(len=:), allocatable :: spool, token, last_error, nonce
  logical :: connected = .false., gone = .false.
  integer :: counter = 0

contains

  function mlink_error() result(text)
    character(len=:), allocatable :: text
    text = ''
    if (allocated(last_error)) text = last_error
  end function mlink_error

  function folder() result(path)
    character(len=:), allocatable :: path
    integer :: length, status
    call get_environment_variable('SPHERE_MLINK', length=length, status=status)
    if (status /= 0 .or. length == 0) then
      path = spx_bridge_folder() // '/mlink'
    else
      allocate(character(len=length) :: path)
      call get_environment_variable('SPHERE_MLINK', path)
    end if
  end function folder

  function read_file(path) result(text)
    character(len=*), intent(in) :: path
    character(len=:), allocatable :: text
    integer :: unit, ios
    integer(int64) :: size
    text = ''
    inquire(file=path, size=size)
    if (size <= 0) return
    open(newunit=unit, file=path, access='stream', form='unformatted', status='old', action='read', iostat=ios)
    if (ios /= 0) return
    text = repeat(' ', int(size))
    read(unit, iostat=ios) text
    close(unit)
    if (ios /= 0) text = ''
  end function read_file

  !> What the gateway's Python holds, from frameworks.txt: starts nothing.
  subroutine mlink_detect(info)
    type(mlink_info), intent(out) :: info
    character(len=:), allocatable :: text
    text = read_file(folder() // '/frameworks.txt')
    info%found = len(text) > 0
    info%python = spx_meta(text, 'python', '')
    info%torch = spx_meta(text, 'torch', '')
    info%tensorflow = spx_meta(text, 'tensorflow', '')
    info%keras = spx_meta(text, 'keras', '')
    info%jax = spx_meta(text, 'jax', '')
    info%pyhf = spx_meta(text, 'pyhf', '')
    info%has_torch = len(info%torch) > 0
    info%has_tensorflow = len(info%tensorflow) > 0
    info%has_keras = len(info%keras) > 0
    info%has_jax = len(info%jax) > 0
    info%has_pyhf = len(info%pyhf) > 0
  end subroutine mlink_detect

  function escaped(text) result(out)
    character(len=*), intent(in) :: text
    character(len=:), allocatable :: out
    integer :: k
    out = ''
    do k = 1, len(text)
      if (text(k:k) == '\') then
        out = out // '\\'
      else if (text(k:k) == achar(10)) then
        out = out // '\n'
      else if (text(k:k) == achar(13)) then
        out = out // '\r'
      else
        out = out // text(k:k)
      end if
    end do
  end function escaped

  function unescaped(text) result(out)
    character(len=*), intent(in) :: text
    character(len=:), allocatable :: out
    integer :: k
    out = ''
    k = 1
    do while (k <= len(text))
      if (text(k:k) == '\' .and. k < len(text)) then
        if (text(k+1:k+1) == 'n') then
          out = out // achar(10)
          k = k + 2
          cycle
        else if (text(k+1:k+1) == 'r') then
          out = out // achar(13)
          k = k + 2
          cycle
        else if (text(k+1:k+1) == '\') then
          out = out // '\'
          k = k + 2
          cycle
        end if
      end if
      out = out // text(k:k)
      k = k + 1
    end do
  end function unescaped

  function shape_text(values) result(out)
    integer, intent(in) :: values(:)
    character(len=64) :: out
    character(len=16) :: one
    integer :: k
    out = ''
    do k = 1, size(values)
      write(one, '(i0)') values(k)
      out = trim(out) // ' ' // trim(one)
    end do
    out = adjustl(out)
  end function shape_text

  !> An array for the next call, under a name its code uses.
  subroutine put_vector(name, values)
    character(len=*), intent(in) :: name
    real(real64), intent(in) :: values(:)
    call put(name, values, [size(values)])
  end subroutine put_vector

  subroutine put_matrix(name, values)
    character(len=*), intent(in) :: name
    real(real64), intent(in) :: values(:, :)
    call put(name, reshape(values, [size(values)]), shape(values))
  end subroutine put_matrix

  subroutine put(name, flat, dims)
    character(len=*), intent(in) :: name
    real(real64), intent(in) :: flat(:)
    integer, intent(in) :: dims(:)
    real(real64), allocatable :: grown(:)
    integer :: held
    if (put_count >= most) stop 'sphere_mlink: too many arrays for one call'
    held = sum(put_lengths(1:put_count))
    allocate(grown(held + size(flat)))
    if (held > 0) grown(1:held) = put_data(1:held)
    grown(held + 1:) = flat
    call move_alloc(grown, put_data)
    put_count = put_count + 1
    put_names(put_count) = name
    put_lengths(put_count) = size(flat)
    put_shapes(put_count) = shape_text(dims)
  end subroutine put

  subroutine forget_puts()
    put_count = 0
    put_lengths = 0
    if (allocated(put_data)) deallocate(put_data)
  end subroutine forget_puts

  subroutine nap(microseconds)
    integer, intent(in) :: microseconds
    integer(c_int) :: status
    status = c_usleep(int(microseconds, c_int))
  end subroutine nap

  function clock_seconds() result(t)
    real(real64) :: t
    integer(int64) :: count, rate
    call system_clock(count, rate)
    t = real(count, real64) / real(rate, real64)
  end function clock_seconds

  subroutine start_gateway()
    character(len=:), allocatable :: script
    character(len=16) :: os
    logical :: there
    integer :: k, status
    os = ''
    call get_environment_variable('OS', os)
    if (trim(os) == 'Windows_NT') then
      script = spool // '/start.bat'
      do k = 1, len(script)
        if (script(k:k) == '/') script(k:k) = '\'
      end do
      inquire(file=script, exist=there)
      if (there) call execute_command_line('"' // script // '"', wait=.true., exitstat=status)
    else
      script = spool // '/start.sh'
      inquire(file=script, exist=there)
      if (there) call execute_command_line("sh '" // script // "'", wait=.true., exitstat=status)
    end if
  end subroutine start_gateway

  subroutine read_token()
    token = spx_meta(read_file(spool // '/gateway.txt'), 'Token', '')
  end subroutine read_token

  subroutine connect(ok)
    logical, intent(out) :: ok
    real(real64) :: deadline
    character(len=:), allocatable :: reply, meta, beat
    logical :: started, answered
    character(len=16) :: stamp
    integer(int64) :: tick
    ok = connected
    if (connected) return
    spool = folder()
    call system_clock(tick)
    write(stamp, '(z0)') iand(tick, 16777215_int64)
    nonce = trim(stamp)
    deadline = clock_seconds() + 120.0_real64
    started = .false.
    do
      call read_token()
      if (len(token) > 0) then
        call send('ping', '', reply, meta, answered, 2.0_real64)
        if (.not. answered) then      ! busy with someone's long computation: it will come to this one
          beat = read_file(spool // '/heartbeat.txt')
          call nap(1500000)
          answered = len(beat) > 0 .and. beat /= read_file(spool // '/heartbeat.txt')
        end if
        if (answered) then
          connected = .true.
          ok = .true.
          return
        end if
      end if
      if (.not. started) then
        call start_gateway()
        started = .true.
      end if
      if (clock_seconds() > deadline) then
        last_error = 'no gateway answers in ' // spool // ". In Sphere: ':ml link start'."
        return
      end if
      call nap(50000)
    end do
  end subroutine connect

  !> Sends what mlink_put gathered and waits. The arrays stay until the call is over: they may have to be sent again.
  subroutine send(op, fields, reply, meta, ok, timeout)
    character(len=*), intent(in) :: op, fields
    character(len=:), allocatable, intent(out) :: reply, meta
    logical, intent(out) :: ok
    real(real64), intent(in) :: timeout
    character(len=:), allocatable :: header, id, request
    character(len=24), allocatable :: names(:)
    real(real64), allocatable :: columns(:, :)
    character(len=32) :: text
    integer :: k, at, longest, pause, unit, ios
    real(real64) :: started, seen, now
    character(len=:), allocatable :: beat, current
    logical :: there, kicked
    ok = .false.
    kicked = .false.
    gone = .false.
    counter = counter + 1
    write(text, '(i0,"-",i0,"-")') getpid(), counter
    id = trim(text) // nonce
    header = 'Op: ' // op // achar(10) // 'Protocol: 1' // achar(10) // 'Token: ' // token // achar(10) // &
             'Order: F' // achar(10) // fields
    longest = max(1, maxval(put_lengths))
    allocate(names(put_count), columns(longest, put_count))
    at = 0
    do k = 1, put_count
      names(k) = put_names(k)
      header = header // 'Shape.' // trim(put_names(k)) // ': ' // trim(put_shapes(k)) // achar(10)
      if (put_lengths(k) > 0) columns(1:put_lengths(k), k) = put_data(at + 1:at + put_lengths(k))
      at = at + put_lengths(k)
    end do
    request = spool // '/in/' // id // '.spx'
    call spx_write_table(request, 'mlink', header, names, columns, put_lengths(1:put_count))
    reply = spool // '/out/' // id // '.spx'
    started = clock_seconds()
    seen = started
    beat = read_file(spool // '/heartbeat.txt')
    pause = 100
    do
      inquire(file=reply, exist=there)
      if (there) exit
      call nap(pause)
      pause = min(pause * 3 / 2 + 1, 20000)
      now = clock_seconds()
      if (timeout > 0 .and. now - started > timeout) then
        last_error = "no answer to '" // op // "'"
        return
      end if
      if (now - seen > 5.0_real64) then
        current = read_file(spool // '/heartbeat.txt')
        if (spx_meta(read_file(spool // '/gateway.txt'), 'Token', '') /= token) then
          last_error = 'the gateway stopped before answering'
          gone = .true.
          return
        end if
        if (current /= beat) then
          beat = current
          seen = now
          kicked = .false.
        else
          if (.not. kicked .and. now - seen > 15.0_real64) then   ! dead, it is replaced; alive, its lock turns the new one away
            call start_gateway()
            kicked = .true.
          end if
          if (now - seen > 90.0_real64) then
            last_error = 'the gateway has stopped answering (see gateway.log in ' // spool // ')'
            return
          end if
        end if
      end if
    end do
    do k = 1, 500                     ! a scanner may hold a new file for a moment (Windows)
      open(newunit=unit, file=reply, access='stream', form='unformatted', status='old', action='read', iostat=ios)
      if (ios == 0) then
        close(unit)
        exit
      end if
      call nap(10000)
    end do
    call spx_read_meta(reply, meta, ok)
    if (.not. ok) last_error = 'unreadable answer ' // reply
  end subroutine send

  subroutine remove_file(path)
    character(len=*), intent(in) :: path
    integer :: unit, ios
    open(newunit=unit, file=path, status='old', iostat=ios)
    if (ios == 0) close(unit, status='delete', iostat=ios)
  end subroutine remove_file

  !> A request and its answer: the gateway's refusals are reported, a restarted gateway asked again.
  subroutine exchange(op, fields, reply, meta, ok)
    character(len=*), intent(in) :: op, fields
    character(len=:), allocatable, intent(out) :: reply, meta
    logical, intent(out) :: ok
    call connect(ok)
    if (.not. ok) then
      call forget_puts()
      return
    end if
    call read_token()                 ! the gateway stops when idle: the next call starts it again
    if (len(token) == 0) then
      connected = .false.
      call connect(ok)
      if (.not. ok) then
        call forget_puts()
        return
      end if
    end if
    call send(op, fields, reply, meta, ok, 0.0_real64)
    if (gone) then                    ! replaced while this one waited: asked again, of the new one
      connected = .false.
      call connect(ok)
      if (ok) call send(op, fields, reply, meta, ok, 0.0_real64)
      if (.not. ok) then
        call forget_puts()
        return
      end if
    end if
    if (ok .and. spx_meta(meta, 'Retry', '0') == '1') then
      call remove_file(reply)
      call read_token()
      call send(op, fields, reply, meta, ok, 0.0_real64)
    end if
    call forget_puts()
    if (.not. ok) return
    if (spx_meta(meta, 'Ok', '0') /= '1') then
      last_error = unescaped(spx_meta(meta, 'Error', 'the gateway refused'))
      ok = .false.
      call remove_file(reply)
    end if
  end subroutine exchange

  function code_fields(code, session) result(fields)
    character(len=*), intent(in) :: code
    character(len=*), intent(in), optional :: session
    character(len=:), allocatable :: fields
    fields = 'Code: ' // escaped(trim(code)) // achar(10)
    if (present(session)) fields = fields // 'Session: ' // trim(session) // achar(10)
  end function code_fields

  !> Runs Python for what it does, its value left aside.
  subroutine mlink_exec(code, ok, session)
    character(len=*), intent(in) :: code
    logical, intent(out) :: ok
    character(len=*), intent(in), optional :: session
    character(len=:), allocatable :: reply, meta
    call exchange('py', code_fields(code, session), reply, meta, ok)
    if (ok) call remove_file(reply)
  end subroutine mlink_exec

  subroutine eval_number(code, value, ok, session)
    character(len=*), intent(in) :: code
    real(real64), intent(out) :: value
    logical, intent(out) :: ok
    character(len=*), intent(in), optional :: session
    character(len=:), allocatable :: reply, meta, item
    integer :: ios
    value = 0
    call exchange('py', code_fields(code, session), reply, meta, ok)
    if (.not. ok) return
    if (spx_meta(meta, 'Type', '') /= 'number') then
      last_error = 'the result is not a number but of type ' // spx_meta(meta, 'Type', '?')
      ok = .false.
    else
      item = spx_meta(meta, 'Value', '')
      read(item, *, iostat=ios) value
      ok = ios == 0
    end if
    call remove_file(reply)
  end subroutine eval_number

  subroutine first_array(reply, meta, flat, dims, ok)
    character(len=*), intent(in) :: reply, meta
    real(real64), allocatable, intent(out) :: flat(:)
    integer, allocatable, intent(out) :: dims(:)
    logical, intent(out) :: ok
    character(len=:), allocatable :: text
    integer :: n, ios, k
    real(real64), allocatable :: column(:)
    call spx_column(reply, 'a0', column, ok)
    if (.not. ok) then
      last_error = 'the result holds no array'
      return
    end if
    allocate(flat(size(column)))
    flat = column
    text = spx_meta(meta, 'Shape.a0', '')
    n = 0
    do k = 1, len(text)
      if (text(k:k) /= ' ' .and. (k == 1 .or. text(max(k-1, 1):max(k-1, 1)) == ' ')) n = n + 1
    end do
    allocate(dims(n))
    read(text, *, iostat=ios) dims
    ok = ios == 0
  end subroutine first_array

  subroutine eval_vector(code, values, ok, session)
    character(len=*), intent(in) :: code
    real(real64), allocatable, intent(out) :: values(:)
    logical, intent(out) :: ok
    character(len=*), intent(in), optional :: session
    character(len=:), allocatable :: reply, meta
    integer, allocatable :: dims(:)
    call exchange('py', code_fields(code, session), reply, meta, ok)
    if (.not. ok) return
    call first_array(reply, meta, values, dims, ok)
    call remove_file(reply)
  end subroutine eval_vector

  subroutine eval_matrix(code, values, ok, session)
    character(len=*), intent(in) :: code
    real(real64), allocatable, intent(out) :: values(:, :)
    logical, intent(out) :: ok
    character(len=*), intent(in), optional :: session
    character(len=:), allocatable :: reply, meta
    call exchange('py', code_fields(code, session), reply, meta, ok)
    if (.not. ok) return
    call to_matrix(reply, meta, values, ok)
    call remove_file(reply)
  end subroutine eval_matrix

  subroutine to_matrix(reply, meta, values, ok)
    character(len=*), intent(in) :: reply, meta
    real(real64), allocatable, intent(out) :: values(:, :)
    logical, intent(out) :: ok
    real(real64), allocatable :: flat(:)
    integer, allocatable :: dims(:)
    call first_array(reply, meta, flat, dims, ok)
    if (.not. ok) return
    if (size(dims) /= 2) then
      last_error = 'the result is not a matrix: it has rank ' // shape_text([size(dims)])
      ok = .false.
      return
    end if
    allocate(values(dims(1), dims(2)))
    values = reshape(flat, [dims(1), dims(2)])
  end subroutine to_matrix

  !> A path as the gateway, whose folder is another, can open it.
  function absolute(path) result(out)
    character(len=*), intent(in) :: path
    character(len=:), allocatable :: out
    character(len=4096) :: here
    logical :: rooted
    rooted = index(path, ':') == 2
    if (len(path) > 0) rooted = rooted .or. path(1:1) == '/' .or. path(1:1) == '\'
    if (rooted) then
      out = path
    else
      call getcwd(here)
      out = trim(here) // '/' // path
    end if
  end function absolute

  function model_fields(model, kind) result(fields)
    character(len=*), intent(in) :: model
    character(len=*), intent(in), optional :: kind
    character(len=:), allocatable :: fields, inputs
    integer :: k
    inputs = ''
    do k = 1, put_count
      inputs = inputs // merge(',', ' ', k > 1) // trim(put_names(k))
    end do
    fields = 'Model: ' // escaped(absolute(trim(model))) // achar(10) // 'Inputs: ' // trim(adjustl(inputs)) // achar(10)
    if (present(kind)) fields = fields // 'Kind: ' // trim(kind) // achar(10)
  end function model_fields

  !> A saved model on the arrays given by mlink_put.
  subroutine predict_vector(model, values, ok, kind)
    character(len=*), intent(in) :: model
    real(real64), allocatable, intent(out) :: values(:)
    logical, intent(out) :: ok
    character(len=*), intent(in), optional :: kind
    character(len=:), allocatable :: reply, meta
    integer, allocatable :: dims(:)
    call exchange('predict', model_fields(model, kind), reply, meta, ok)
    if (.not. ok) return
    call first_array(reply, meta, values, dims, ok)
    call remove_file(reply)
  end subroutine predict_vector

  subroutine predict_matrix(model, values, ok, kind)
    character(len=*), intent(in) :: model
    real(real64), allocatable, intent(out) :: values(:, :)
    logical, intent(out) :: ok
    character(len=*), intent(in), optional :: kind
    character(len=:), allocatable :: reply, meta
    call exchange('predict', model_fields(model, kind), reply, meta, ok)
    if (.not. ok) return
    call to_matrix(reply, meta, values, ok)
    call remove_file(reply)
  end subroutine predict_matrix

  !> One channel of counts by pyhf: CLs at mu (default 1); points > 0 scans mu too, for the 95 % CL upper limit.
  subroutine mlink_pyhf(signal, background, uncertainty, observed, cls_obs, ok, mu, points, mu_hat, cls_expected, upper_limit)
    real(real64), intent(in) :: signal(:), background(:), uncertainty(:), observed(:)
    real(real64), intent(out) :: cls_obs
    logical, intent(out) :: ok
    real(real64), intent(in), optional :: mu
    integer, intent(in), optional :: points
    real(real64), intent(out), optional :: mu_hat, cls_expected, upper_limit
    character(len=:), allocatable :: reply, meta, fields, at, item
    character(len=32) :: text
    real(real64), allocatable :: band(:)
    integer :: ios, scan, k
    real(real64) :: strength
    strength = 1.0_real64
    if (present(mu)) strength = mu
    scan = 0
    if (present(points)) scan = points
    if (present(upper_limit) .and. scan == 0) scan = 41
    write(text, '(es25.17)') strength
    fields = 'Mu: ' // trim(adjustl(text)) // achar(10)
    write(text, '(i0)') scan
    fields = fields // 'Points: ' // trim(text) // achar(10) // 'Backends: numpy' // achar(10)
    call mlink_put('signal', signal)
    call mlink_put('background', background)
    call mlink_put('bkg_uncertainty', uncertainty)
    call mlink_put('observed', observed)
    cls_obs = 0
    call exchange('pyhf', fields, reply, meta, ok)
    if (.not. ok) return
    at = 'R.backends.numpy.'
    if (len(spx_meta(meta, 'S.backends.numpy.error', '')) > 0) then
      last_error = 'pyhf: ' // unescaped(spx_meta(meta, 'S.backends.numpy.error', ''))
      ok = .false.
      call remove_file(reply)
      return
    end if
    item = spx_meta(meta, at // 'cls_obs', '')
    read(item, *, iostat=ios) cls_obs
    ok = ios == 0
    if (present(mu_hat)) then
      item = spx_meta(meta, at // 'mu_hat', '')
      read(item, *, iostat=ios) mu_hat
    end if
    if (present(upper_limit)) then
      item = spx_meta(meta, at // 'upper_limit', '0')
      read(item, *, iostat=ios) upper_limit
    end if
    if (present(cls_expected)) then
      ! the median of the band, as an array of five numbers named backends.numpy.cls_exp
      do k = 0, 8
        write(text, '("a",i0)') k
        if (spx_meta(meta, 'Array.' // trim(text), '') == 'backends.numpy.cls_exp') then
          call spx_column(reply, trim(text), band, ok)
          if (ok) cls_expected = band(lbound(band, 1) + 2)
          exit
        end if
      end do
    end if
    call remove_file(reply)
  end subroutine mlink_pyhf

  !> What ':ml link check' asks of every language: the same requests, the same numbers, written to out.
  subroutine mlink_crosscheck(out)
    character(len=*), intent(in) :: out
    real(real64) :: x(2, 3), values(10), ms(1), first, cls, mu_hat
    real(real64), allocatable :: sines(:)
    character(len=24) :: names(2)
    real(real64) :: columns(10, 2)
    real(real64) :: t0, v
    logical :: ok
    integer :: k
    x = reshape([1.0_real64, 4.0_real64, 2.0_real64, 5.0_real64, 3.0_real64, 6.5_real64], [2, 3])
    call mlink_put('x', x)
    call mlink_eval('torch.linalg.norm(torch.as_tensor(x)).item()', first, ok)
    if (.not. ok) stop 2
    values(1) = first
    call mlink_put('x', x)
    call mlink_eval('float(tf.reduce_sum(tf.constant(x) ** 2))', values(2), ok)
    if (.not. ok) stop 3
    call mlink_pyhf([5.0_real64, 10.0_real64], [50.0_real64, 60.0_real64], [7.0_real64, 8.0_real64], &
                    [53.0_real64, 65.0_real64], cls, ok, mu_hat=mu_hat)
    if (.not. ok) stop 4
    values(3) = cls
    values(4) = mu_hat
    call mlink_put('x', x)
    call mlink_eval('np.sin(x).ravel()', sines, ok)
    if (.not. ok) stop 5
    values(5:10) = sines(1:6)
    t0 = clock_seconds()
    do k = 1, 20
      call mlink_eval('1+1', v, ok)
    end do
    ms(1) = (clock_seconds() - t0) / 20 * 1000
    names(1) = 'values'
    names(2) = 'ms'
    columns = 0
    columns(1:10, 1) = values
    columns(1, 2) = ms(1)
    call spx_write_table(out, 'mlink check', 'Engine: Fortran' // achar(10), names, columns, [10, 1])
  end subroutine mlink_crosscheck

  subroutine mlink_stop()
    character(len=:), allocatable :: reply, meta
    logical :: ok
    call exchange('stop', '', reply, meta, ok)
    if (ok) call remove_file(reply)
    connected = .false.
  end subroutine mlink_stop

end module sphere_mlink
