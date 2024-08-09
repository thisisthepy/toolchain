from pathlib import Path
import tempfile
import tarfile

import zstandard
# pip install zstandard


def extract_zst(archive: Path, out_path: Path):
    """extract .zst file
    works on Windows, Linux, MacOS, etc.
    
    Parameters
    ----------
    archive: pathlib.Path or str
      .zst file to extract
    out_path: pathlib.Path or str
      directory to extract files and directories to
    """

    if zstandard is None:
        raise ImportError("pip install zstandard")

    archive = Path(archive).expanduser()
    out_path = Path(out_path).expanduser().resolve()
    # need .resolve() in case intermediate relative dir doesn't exist

    dctx = zstandard.ZstdDecompressor()

    with tempfile.TemporaryFile(suffix=".tar") as ofh:
        with archive.open("rb") as ifh:
            dctx.copy_stream(ifh, ofh)
        ofh.seek(0)
        with tarfile.open(fileobj=ofh) as z:
            z.extractall(out_path)
