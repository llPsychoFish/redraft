#!/usr/bin/env bash
# Runs the app's REAL native editing code on your PC against a real PDFium build, no phone needed.
# Usage (from the repo root):   bash tools/native-host-test/run_host_tests.sh
# Needs: g++, python3, and `pip install pypdfium2 reportlab`
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/cpp/pdfedit"
HDR="$ROOT/app/src/main/cpp/pdfium-headers"
LIB="$(python3 -c 'import pypdfium2_raw,os;print(os.path.dirname(pypdfium2_raw.__file__))')"
OUT="$HERE/build"; mkdir -p "$OUT"; cd "$OUT"

python3 "$HERE/mkpdfs.py"                       # writes A_single.pdf and B_perword.pdf (needs DejaVu fonts)
g++ -std=c++17 -O1 -I"$HDR" -I"$SRC" "$HERE/edit_one_object_test.cpp" \
    "$SRC/EditingDocument.cpp" "$SRC/TextMutation.cpp" "$SRC/Save.cpp" \
    -L"$LIB" -l:libpdfium.so -Wl,-rpath,"$LIB" -o one_object_test
g++ -std=c++17 -O1 -I"$HDR" -I"$SRC" "$HERE/edit_multi_object_prototype.cpp" \
    "$SRC/EditingDocument.cpp" "$SRC/Save.cpp" \
    -L"$LIB" -l:libpdfium.so -Wl,-rpath,"$LIB" -o multi_object_prototype
g++ -std=c++17 -O1 -I"$HDR" -I"$SRC" "$HERE/line_edit_test.cpp" \
    "$SRC/EditingDocument.cpp" "$SRC/TextMutation.cpp" "$SRC/Save.cpp" \
    -L"$LIB" -l:libpdfium.so -Wl,-rpath,"$LIB" -o line_edit_test
g++ -std=c++17 -O1 -I"$HDR" -I"$SRC" "$HERE/replacement_edit_test.cpp" \
    "$SRC/EditingDocument.cpp" "$SRC/TextMutation.cpp" "$SRC/Save.cpp" \
    -L"$LIB" -l:libpdfium.so -Wl,-rpath,"$LIB" -o replacement_edit_test

# The two cases from the review: a one-object line and a one-object-per-word line.
./line_edit_test A_single.pdf A_edited.pdf "Arnold Sampson"
./line_edit_test B_perword.pdf B_edited.pdf "Arnold Sampson"

# The replacement path with a bundled font: same origin, same height, same size.
FONT="$ROOT/app/src/main/assets/fonts/liberation/LiberationSans-Regular.ttf"
# Same text: the ink box is comparable, so the height check is meaningful.
./replacement_edit_test A_single.pdf A_replaced.pdf "$FONT" "KOFORIDUA TECHNICAL UNIVERSITY"
./replacement_edit_test B_perword.pdf B_replaced.pdf "$FONT" "KOFORIDUA TECHNICAL UNIVERSITY"
# Different text: the new object still starts on the original left edge.
./replacement_edit_test A_single.pdf A_replaced_new.pdf "$FONT" "Arnold Sampson"
echo "ALL HOST TESTS PASSED"
