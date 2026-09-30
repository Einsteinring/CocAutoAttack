"""
Собирает образцы цифр «Доступной добычи» для приложения.

Вход: снимки чужих баз в raw/screens (s06, s07, s16, s17, s18), приведённые к высоте 540
методом INTER_AREA, как делает приложение. В TRUTH записаны числа в рядах золота, эликсира
и чёрного эликсира. Образцы усредняются по всем экземплярам цифры.

Выход: app/src/main/java/com/kotbaton/cocbot/LootDigitData.kt и фрагменты снимков для юнит-теста
в app/src/test/resources (только цифры добычи, без ников).

Запуск из корня проекта:  python tools/make_digit_protos.py
"""
import gzip
import cv2
import numpy as np
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ROW_TOP = [77, 106, 135]      # верх цифр в рядах золота, эликсира, чёрного эликсира при высоте кадра 540
X1, X2 = 96, 190              # где искать цифры по горизонтали
CW, CH = 12, 13               # холст образца: ширина и высота
CUT_FROM = ['s16', 's17', 's18']
TRUTH = {
    's06': ['63881', '271936', '524'], 's07': ['351561', '444968', '8884'],
    's16': ['214124', '104544', '7512'], 's17': ['1388737', '1390128', '8330'],
    's18': ['109802', '790891', '2526'],
}

def load(name):
    im = cv2.imread(str(ROOT / 'raw' / 'screens' / f'{name}.jpg'))
    h = 540
    return cv2.resize(im, (round(im.shape[1] * h / im.shape[0]), h), interpolation=cv2.INTER_AREA)

def whiteness(img):
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV).astype(np.float32)
    s, v = hsv[..., 1] / 255.0, hsv[..., 2] / 255.0
    return np.clip(v * (1 - s) * 1.2, 0, 1)

def segment(img, row):
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    y1, y2 = ROW_TOP[row] - 7, ROW_TOP[row] + 19
    hb = hsv[y1:y2, X1:X2]
    band = (hb[..., 1] < 80) & (hb[..., 2] > 200)
    cols = band.sum(axis=0) >= 1
    runs, x = [], 0
    while x < len(cols):
        if cols[x]:
            a = x
            while x < len(cols) and cols[x]:
                x += 1
            runs.append((a, x))
        else:
            x += 1
    out, prev_end = [], None
    for a, b in runs:
        ys = np.where(band[:, a:b].sum(axis=1) >= 1)[0]
        h = ys.max() - ys.min() + 1
        if h < 9 or h > 14 or (b - a < 3 and h < 10):
            continue
        if prev_end is not None and a - prev_end > 11:
            break
        out.append((a + X1, ys.min() + y1))
        prev_end = b
    return out

def main():
    acc = {}
    for name in CUT_FROM:
        img = load(name)
        w = whiteness(img)
        for row, text in enumerate(TRUTH[name]):
            glyphs = segment(img, row)
            assert len(glyphs) >= len(text), (name, row, len(glyphs), len(text))
            for ch, (x0, y0) in zip(text, glyphs[:len(text)]):
                acc.setdefault(ch, []).append(w[y0 - 1:y0 - 1 + CH, x0 - 1:x0 - 1 + CW])
    assert sorted(acc) == list('0123456789'), sorted(acc)

    lines = [
        'package com.kotbaton.cocbot',
        '',
        '/**',
        ' * Образцы цифр «Доступной добычи»: усреднённая белизна глифа на холсте 12×13, значения 0..9.',
        ' * Сгенерировано tools/make_digit_protos.py, вручную не править.',
        ' */',
        'internal object LootDigitData {',
        '    const val WIDTH = %d' % CW,
        '    const val HEIGHT = %d' % CH,
        '    val ROW_TOP = intArrayOf(%s)' % ', '.join(map(str, ROW_TOP)),
        '',
        '    val PROTOS: Map<Char, List<String>> = mapOf(',
    ]
    for d in '0123456789':
        m = np.mean(acc[d], axis=0)
        rows = [''.join(str(int(round(float(v) * 9))) for v in r) for r in m]
        lines.append("        '%s' to listOf(" % d)
        for r in rows:
            lines.append('            "%s",' % r)
        lines.append('        ),')
    lines += ['    )', '}', '']
    (ROOT / 'app/src/main/java/com/kotbaton/cocbot/LootDigitData.kt').write_text('\n'.join(lines), encoding='utf-8', newline='\n')
    print('образцы:', {d: len(v) for d, v in sorted(acc.items())})

    # Фрагменты кадров для юнит-теста: только область с числами добычи.
    res = ROOT / 'app/src/test/resources'
    res.mkdir(parents=True, exist_ok=True)
    for name in TRUTH:
        img = load(name)
        y0, y1, x0, x1 = 60, 165, 90, 200
        crop = cv2.cvtColor(img[y0:y1, x0:x1], cv2.COLOR_BGR2RGB)
        with gzip.open(res / f'loot_{name}.ppm.gz', 'wb') as f:
            f.write(f'P6\n{x1 - x0} {y1 - y0}\n255\n'.encode() + crop.tobytes())
    print('фрагменты для теста записаны')

if __name__ == '__main__':
    main()
