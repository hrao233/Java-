"""Generate disposable audit data and capture native Windows window pixels."""

import argparse
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import time
import zipfile

from PIL import Image, ImageDraw, ImageGrab


def document(path):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", """
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".strip())
        archive.writestr("_rels/.rels", """
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".strip())
        archive.writestr("word/document.xml", """
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>Project Aster | Field study plan</w:t></w:r></w:p>
    <w:p><w:r><w:t>Survey three routes and archive measurements, notes and photographs.</w:t></w:r></w:p>
    <w:sectPr/>
  </w:body>
</w:document>""".strip())


def pictures(project):
    for name, color, peaks in [
        ("路线概览.png", "#007a77", [(0, 445), (210, 198), (387, 381), (646, 114), (900, 408)]),
        ("River Survey.png", "#27568a", [(0, 420), (255, 133), (480, 340), (660, 202), (900, 390)]),
        ("路线备选.png", "#8b6049", [(0, 385), (150, 243), (420, 180), (650, 342), (900, 280)]),
    ]:
        image = Image.new("RGB", (900, 580), "#edf5f2")
        art = ImageDraw.Draw(image)
        art.ellipse((680, 54, 820, 194), fill="#f4c66b")
        art.polygon([*peaks, (900, 580), (0, 580)], fill=color)
        art.polygon([(0, 482), (215, 461), (460, 546), (610, 484), (900, 451),
                     (900, 580), (0, 580)], fill="#153e4c")
        art.line([(120, 468), (280, 389), (461, 415), (615, 273), (758, 400)], fill="#ffffff", width=5)
        for x, y in [(120, 468), (461, 415), (758, 400)]:
            art.ellipse((x - 11, y - 11, x + 11, y + 11), fill="#f4c66b")
        art.text((33, 35), "ASTER FIELD STUDY  /  ROUTE OVERVIEW", fill="#264755")
        image.save(project / name)


def create_fixture(root):
    from openpyxl import Workbook
    from reportlab.pdfgen import canvas

    root.mkdir()
    for name in ("Workspace", "Profile", "Cache", "Temp", "Transfer", "Volume",
                 "Profile/Desktop", "Profile/Downloads", "Profile/Documents", "Profile/Pictures",
                 "Profile/Music", "Profile/Videos", "Workspace/Project Aster", "Workspace/交付资料",
                 "Workspace/Empty folder", "Workspace/Batch index", "Workspace/编辑与评审工作目录"):
        (root / name).mkdir(parents=True, exist_ok=True)
    project = root / "Workspace/Project Aster"
    for name in ("01_会议纪要", "02_原始数据", "03_素材参考", "04_备份"):
        (project / name).mkdir()
    long_path = root / "Workspace/编辑与评审工作目录"
    for segment in ("项目资料整理与版本记录", "第一批路线调研归档与观测照片",
                    "共享评审使用的源文件清单", "现场记录和附录待提交版本",
                    "核对完成后的资料与存档交接"):
        long_path /= segment
        long_path.mkdir()
    notes = (
        "ASTER 项目现场笔记\n\n"
        "日期  2026-11-24\n地点  松林步道西段\n\n"
        "09:20  记录起点与水位\n"
        "10:05  对照旧版路线图\n"
        "11:30  归档三组路线样图\n\n"
        "待办\n· 核对预算表中的交通项\n· 加入路线备选图\n· 归档 PDF 与会议纪要\n"
    )
    (project / "Field Notes.txt").write_text(notes, encoding="utf-8")
    (project / "01_会议纪要/周二讨论.txt").write_text(notes, encoding="utf-8")
    (long_path / "评审交接清单.txt").write_text(notes, encoding="utf-8")
    document(project / "项目计划.docx")
    document(project / "项目调研阶段成果交接清单与版本变更记录_供项目组评审和归档_最终校对版.docx")
    book = Workbook()
    book.active.title = "Route budget"
    for row in [("Route", "Transport", "Materials"), ("North", 520, 180), ("River", 410, 240), ("West", 630, 95)]:
        book.active.append(row)
    book.save(project / "Budget Q4.xlsx")
    report = canvas.Canvas(str(project / "Field Report.pdf"))
    for number in range(1, 5):
        report.setFont("Helvetica-Bold", 21)
        report.drawString(60, 760, f"Aster field report  |  {number:02}")
        report.setFont("Helvetica", 12)
        report.drawString(60, 729, "Route comparison, field notes and handover checklist.")
        report.showPage()
    report.save()
    pictures(project)
    (project / "RouteSpec.java").write_text(
        'record RouteSpec(String name, long kilometres) { }\n', encoding="utf-8")
    (project / "summary report.py").write_text(
        'for route in ("North", "River", "West"):\n    print(f"Prepared: {route}")\n', encoding="utf-8")
    (project / "tasks.csv").write_text("Route,Status\nNorth,Done\nRiver,Review\nWest,Pending\n", encoding="utf-8")
    (project / "02_原始数据/route.csv").write_text("Longitude,Level\n98.1,12.4\n98.8,12.9\n", encoding="utf-8")
    with zipfile.ZipFile(project / "Archive 2026.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(project / "Field Report.pdf", "Field Report.pdf")
        archive.write(project / "Field Notes.txt", "Field Notes.txt")
        archive.write(project / "River Survey.png", "River Survey.png")
    for number, path in enumerate(sorted(project.iterdir())):
        changed = 1775030400 + number * 11 * 86400
        os.utime(path, (changed, changed))
    (root / "Workspace/交付资料/交付说明.txt").write_text(notes, encoding="utf-8")
    for index in range(1500):
        path = root / "Workspace/Batch index" / f"ASTER-observation-{index + 1:04d}.csv"
        path.write_text(f"Index,Level\n{index + 1},{10 + index % 5}.5\n", encoding="utf-8")
    for name in ("Source", "Single", "Batch", "Conflict", "Partial", "Cancel", "Failed", "Unknown"):
        (root / "Transfer" / name).mkdir()
    source = root / "Transfer/Source"
    (source / "01_交付说明.txt").write_text(notes, encoding="utf-8")
    (source / "02_路线观察.txt").write_text("项目交付资料 | 路线观察数据\n", encoding="utf-8")
    (root / "Transfer/Conflict/02_路线观察.txt").write_text("同名目标 | 原来的路线观察\n", encoding="utf-8")
    with (source / "03_路线原始样本.dat").open("wb") as data:
        block = bytes(range(256)) * 4096
        for _ in range(32):
            data.write(block)


def capture(pid, destination, include_owned):
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    user32.SetThreadDpiAwarenessContext.argtypes = [wintypes.HANDLE]
    user32.SetThreadDpiAwarenessContext.restype = wintypes.HANDLE
    user32.SetThreadDpiAwarenessContext(wintypes.HANDLE(-4))
    user32.GetWindow.argtypes = [wintypes.HWND, wintypes.UINT]
    user32.GetWindow.restype = wintypes.HWND
    user32.SetWindowPos.argtypes = [
        wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.UINT
    ]
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.IsIconic.argtypes = [wintypes.HWND]
    user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.GetDpiForWindow.argtypes = [wintypes.HWND]
    user32.GetDpiForWindow.restype = wintypes.UINT
    dwmapi = ctypes.WinDLL("dwmapi")
    dwmapi.DwmGetWindowAttribute.argtypes = [wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]

    def owner(hwnd):
        process = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(process))
        return process.value

    handles = []
    visible = []
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    @callback_type
    def find_main(hwnd, _):
        title = ctypes.create_unicode_buffer(120)
        if user32.IsWindowVisible(hwnd) and owner(hwnd) == pid:
            visible.append(hwnd)
            user32.GetWindowTextW(hwnd, title, len(title))
            if title.value == "本地文件管理器":
                handles.append(hwnd)
        return True

    user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
    user32.EnumWindows(find_main, 0)
    if len(handles) != 1:
        raise RuntimeError("Expected one visible audit application window.")

    window = handles[0]
    def native_frame(hwnd):
        frame = wintypes.RECT()
        if dwmapi.DwmGetWindowAttribute(hwnd, 9, ctypes.byref(frame), ctypes.sizeof(frame)):
            return None
        return frame

    frame = native_frame(window)
    if frame is None:
        raise RuntimeError("Unable to read the native window frame.")
    if include_owned:
        for hwnd in visible:
            if user32.GetWindow(hwnd, 4) != window:
                continue
            owned = native_frame(hwnd)
            if owned:
                frame.left = min(frame.left, owned.left)
                frame.top = min(frame.top, owned.top)
                frame.right = max(frame.right, owned.right)
                frame.bottom = max(frame.bottom, owned.bottom)
    bounds = (frame.left, frame.top, frame.right, frame.bottom)
    display = (user32.GetSystemMetrics(0), user32.GetSystemMetrics(1))
    if not (0 <= frame.left < frame.right <= display[0] and 0 <= frame.top < frame.bottom <= display[1]):
        raise RuntimeError(f"The audit window is partly off the primary display: {bounds}, {display}")
    def unobstructed():
        above = user32.GetWindow(window, 3)
        while above:
            if owner(above) != pid and user32.IsWindowVisible(above) and not user32.IsIconic(above):
                cloaked = wintypes.DWORD()
                dwmapi.DwmGetWindowAttribute(above, 14, ctypes.byref(cloaked), ctypes.sizeof(cloaked))
                other = native_frame(above)
                if other and not cloaked.value and (
                        max(frame.left, other.left) < min(frame.right, other.right)
                        and max(frame.top, other.top) < min(frame.bottom, other.bottom)):
                    return False
            above = user32.GetWindow(above, 3)
        return True

    if not unobstructed():
        # Only reposition the audit window in the Z order. Do not send keyboard or mouse input.
        user32.SetWindowPos(window, wintypes.HWND(-1), 0, 0, 0, 0, 0x13)
        time.sleep(0.12)
    dwmapi.DwmFlush()
    if not unobstructed():
        raise RuntimeError("Another window overlaps the audit application.")
    picture = ImageGrab.grab(bbox=bounds, include_layered_windows=True)
    if not unobstructed():
        raise RuntimeError("Another window overlapped the audit application during capture.")
    if picture.size != (frame.right - frame.left, frame.bottom - frame.top):
        raise RuntimeError("The capture did not preserve the native pixels.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    picture.save(destination, "PNG")
    print(f"{picture.width}\t{picture.height}\t{user32.GetDpiForWindow(window)}\t{display[0]}\t{display[1]}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["fixture", "capture"])
    parser.add_argument("path", type=Path)
    parser.add_argument("--pid", type=int)
    parser.add_argument("--include-owned", action="store_true")
    args = parser.parse_args()
    if args.action == "fixture":
        create_fixture(args.path)
    else:
        capture(args.pid, args.path, args.include_owned)
