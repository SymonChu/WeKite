#!/usr/bin/env python3
"""所有模块弹窗的 ListItem 容器色改透明 (露出弹窗白/黑底色), 跳过注入微信 UI 的文件。
用法: python3 scripts/batch_listitem_transparent.py
"""
import subprocess, re, sys

# 注入微信 UI 的菜单 (非模块弹窗) —— 不透明, 跟随微信主题
SKIP = ("WeChatInputBarMenuApi.kt", "WeChatMessageContextMenuApi.kt")

files = [
    f for f in subprocess.run(
        ['grep', '-rl', r'ListItem(', 'app/src/main/java', '--include=*.kt'],
        capture_output=True, text=True).stdout.split()
    if not any(s in f for s in SKIP)
]

COLORS_LINE = "colors = ListItemDefaults.colors(containerColor = Color.Transparent),"
IMPORT_LID = "import androidx.compose.material3.ListItemDefaults\n"
IMPORT_COLOR = "import androidx.compose.ui.graphics.Color\n"


def find_block_end(src, start):
    """从 ListItem( 的 ( 开始配对括号, 返回结束下标"""
    depth = 0
    i = src.index('(', start)
    while i < len(src):
        c = src[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def add_import(src, imp, anchor_pattern):
    if imp.strip() in src:
        return src, False
    anchor = re.search(anchor_pattern, src, re.M)
    if anchor:
        return src[:anchor.end()] + imp + src[anchor.end():], True
    a2 = re.search(r'^import ', src, re.M)
    if a2:
        return src[:a2.start()] + imp + src[a2.start():], True
    return src, False


for f in sorted(files):
    src = open(f).read()
    n_added = 0
    # 找到所有 ListItem( 位置
    positions = [m.start() for m in re.finditer(r'ListItem\(', src)]
    for pos in reversed(positions):  # 从后往前插, 下标不失效
        end = find_block_end(src, pos + len('ListItem'))
        if end == -1:
            continue
        block = src[pos:end]
        if 'ListItemDefaults' in block:
            continue  # 已有透明容器色
        # 在 ListItem( 后插入 colors 行, 缩进跟随 ListItem 行
        line_start = src.rfind('\n', 0, pos) + 1
        indent = src[line_start:pos]
        insert_at = pos + len('ListItem(')
        # ListItem( 后可能是换行或直接参数; 统一加换行
        if src[insert_at] == '\n':
            src = src[:insert_at + 1] + indent + '    ' + COLORS_LINE + '\n' + src[insert_at + 1:]
        else:
            src = src[:insert_at] + '\n' + indent + '    ' + COLORS_LINE + '\n' + src[insert_at:]
        n_added += 1

    changed = n_added > 0
    if 'ListItemDefaults.colors' in src:
        src, c1 = add_import(src, IMPORT_LID, r'^import androidx\.compose\.material3\.\S+\n')
        changed = changed or c1
        if 'Color.Transparent' in src and 'import androidx.compose.ui.graphics.Color' not in src:
            src, c2 = add_import(src, IMPORT_COLOR, r'^import androidx\.compose\.ui\.\S+\n')
            changed = changed or c2
    if changed:
        open(f, 'w').write(src)
        print(f'{f}: +{n_added} ListItem transparent')
print('DONE')
