# Third-party notices

SimpleRecorder downloads the official `avifenc` artifact from
[libavif v1.4.2](https://github.com/AOMediaCodec/libavif/releases/tag/v1.4.2)
at runtime when no local encoder is present. The binary is not redistributed
inside the mod jar. It is statically linked and includes software from the
projects below. Downloaded files are cached under
`config/simplerecorder/avif/<platform>/`.

## libavif

BSD 2-Clause License

Copyright 2019 Joe Drago. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## AOM (AV1 encoder)

BSD 3-Clause License. Copyright (c) 2016, Alliance for Open Media. All rights
reserved. See <https://aomedia.googlesource.com/aom/+/refs/heads/main/LICENSE>
for the full text.

## dav1d (AV1 decoder), libyuv, libjpeg-turbo

BSD 2-Clause / BSD 3-Clause licenses. See:

- <https://code.videolan.org/videolan/dav1d/-/blob/master/COPYING>
- <https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/LICENSE>
- <https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/LICENSE.md>

## libpng, zlib

zlib licenses. See:

- <https://github.com/pnggroup/libpng/blob/libpng16/LICENSE>
- <https://zlib.net/zlib_license.html>

The binaries are redistributed unmodified. SimpleRecorder's own source code is
licensed under the GNU General Public License v3.0 only (see `LICENSE`).
