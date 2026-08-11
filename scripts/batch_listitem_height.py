#!/usr/bin/env python3
"""给用了 Modifier.height(48.dp) 的文件补齐 import height/dp"""
import subprocess, re

files = subprocess.run(
    ['grep', '-rl', r'Modifier\.height(48\.dp)\.', 'app/src/main/java', '--include=*.kt'],
    capture_output=True, text=True).stdout.split()
# 也匹配 .height(48.dp) 结尾的 (Modifier.height(48.dp), )
files2 = subprocess.run(
    ['grep', '-rl', r'height\(48\.dp\)', 'app/src/main/java', '--include=*.kt'],
    capture_output=True, text=True).stdout.split()
files = sorted(set(files + files2))

HEIGHT_IMPORT = "import androidx.compose.foundation.layout.height\n"
DP_IMPORT = "import androidx.compose.ui.unit.dp\n"

for f in files:
    src = open(f).read()
    changed = False
    if 'Modifier.height(' in src or '.height(' in src:
        if 'androidx.compose.foundation.layout.height' not in src:
            # 插到 foundation.layout 相关 import 后
            anchor = re.search(r'^import androidx\.compose\.foundation\.layout\.\S+\n', src, re.M)
            if anchor:
                src = src[:anchor.end()] + HEIGHT_IMPORT + src[anchor.end():]
            else:
                # 插到 foundation 组第一个 import 前
                anchor2 = re.search(r'^import androidx\.compose\.foundation\.\S+\n', src, re.M)
                if anchor2:
                    src = src[:anchor2.start()] + HEIGHT_IMPORT + src[anchor2.start():]
                else:
                    src = src.replace('import androidx.compose.ui.Alignment',
                                      HEIGHT_IMPORT + 'import androidx.compose.ui.Alignment', 1)
            changed = True
        if 'androidx.compose.ui.unit.dp' not in src:
            anchor3 = re.search(r'^import androidx\.compose\.ui\.\S+\n', src, re.M)
            if anchor3:
                src = src[:anchor3.end()] + DP_IMPORT + src[anchor3.end():]
            else:
                # 无任何 ui import: 插到第一个 import 前
                anchor4 = re.search(r'^import ', src, re.M)
                if anchor4:
                    src = src[:anchor4.start()] + DP_IMPORT + src[anchor4.start():]
            changed = True
    if changed:
        open(f, 'w').write(src)
        print(f'imports added: {f}')
print('DONE')
