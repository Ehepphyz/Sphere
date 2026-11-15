"""python -m sphere_kernel -f connection.json  |  python -m sphere_kernel install [...]"""

import argparse
import json
import sys


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "install":
        from .install import main as install
        return install(argv[1:])
    if argv and argv[0] in ("--version", "-V"):
        from . import __version__
        print(__version__)
        return 0
    parser = argparse.ArgumentParser(prog="sphere_kernel", description="Sphere HEP Jupyter kernel")
    parser.add_argument("-f", "--connection-file", required=True, help="the connection file Jupyter wrote")
    args, _unknown = parser.parse_known_args(argv)
    with open(args.connection_file, "r", encoding="utf-8") as f:
        connection = json.load(f)
    from .kernel import Kernel
    Kernel(connection).start()
    return 0


if __name__ == "__main__":
    sys.exit(main())
