#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 Markdown 文档中所有表格的列数一致性。

背景：中文文档里的表格常因单元格内出现未转义的竖线、或改写整行时留下残余字符，
      导致某一行列数与表头不一致。渲染出来会整块错位，肉眼在长表格里极难发现。

用法：
    python check-md-tables.py <文件或目录> [...]
    不传参数时检查当前目录。

退出码：0 = 全部正常；1 = 存在异常（并打印行号与列数）。
"""
import re
import sys
from pathlib import Path

try:  # 避免 Windows 控制台把中文输出成乱码
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

SEP = re.compile(r'^\s*\|[\s:|-]+\|\s*$')


def ncols(line: str) -> int:
    """一行的列数（去掉首尾竖线后按竖线切分）。"""
    return len(line.strip().strip('|').split('|'))


def check(path: Path):
    lines = path.read_text(encoding='utf-8').split('\n')
    bad, tables, i = [], 0, 0
    while i < len(lines):
        # 表头 + 分隔行 => 一张表的开始
        if lines[i].strip().startswith('|') and i + 1 < len(lines) and SEP.match(lines[i + 1]):
            tables += 1
            head = ncols(lines[i])
            j = i + 1
            while j < len(lines) and lines[j].strip().startswith('|'):
                c = ncols(lines[j])
                if c != head:
                    bad.append((j + 1, c, head, lines[j][:70]))
                j += 1
            i = j
        else:
            i += 1
    return tables, bad, len(lines)


def main(argv):
    targets = []
    for a in argv:
        p = Path(a)
        if p.is_dir():
            targets += sorted(p.rglob('*.md'))
        elif p.is_file():
            targets.append(p)
        else:
            print(f'[跳过] 路径不存在: {a}')
    total = 0
    for p in targets:
        tables, bad, n = check(p)
        print(f'{p}  表格 {tables} 张 / {n} 行 / ' + ('正常' if not bad else f'异常 {len(bad)} 处'))
        for line_no, got, exp, text in bad:
            print(f'    第 {line_no} 行：列数 {got}，应为 {exp}  ->  {text}')
        total += len(bad)
    print(f'合计异常：{total}')
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:] or ['.']))
