"""Turns docs/MANUAL.md into docs/Car-Dash-Manual.pdf (needs: pip install reportlab; DejaVu fonts).
Usage: python docs/make_pdf.py docs/MANUAL.md docs/Car-Dash-Manual.pdf"""
import re, sys
from reportlab.lib.pagesizes import A4
from reportlab.lib import colors
from reportlab.lib.units import mm
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, HRFlowable, ListFlowable, ListItem, KeepTogether
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.fonts import addMapping

D = '/usr/share/fonts/truetype/dejavu/'
pdfmetrics.registerFont(TTFont('DV', D + 'DejaVuSans.ttf'))
pdfmetrics.registerFont(TTFont('DV-B', D + 'DejaVuSans-Bold.ttf'))
pdfmetrics.registerFont(TTFont('DV-I', D + 'DejaVuSans.ttf'))       # no oblique sans installed: plain
pdfmetrics.registerFont(TTFont('DVM', D + 'DejaVuSansMono.ttf'))
addMapping('DV', 0, 0, 'DV'); addMapping('DV', 1, 0, 'DV-B'); addMapping('DV', 0, 1, 'DV-I'); addMapping('DV', 1, 1, 'DV-B')

GREEN = colors.HexColor('#0B8A3A'); DARK = colors.HexColor('#111111'); GREY = colors.HexColor('#555555')
LIGHT = colors.HexColor('#EEF7F0'); LINE = colors.HexColor('#B9D9C2')
base = ParagraphStyle('base', fontName='DV', fontSize=10, leading=14.5, textColor=DARK, spaceAfter=5)
st = {
    'title': ParagraphStyle('title', parent=base, fontName='DV-B', fontSize=26, leading=32, textColor=GREEN, spaceAfter=8),
    'h2': ParagraphStyle('h2', parent=base, fontName='DV-B', fontSize=16, leading=21, textColor=GREEN, spaceBefore=10, spaceAfter=6),
    'h3': ParagraphStyle('h3', parent=base, fontName='DV-B', fontSize=12, leading=16, textColor=DARK, spaceBefore=8, spaceAfter=4),
    'p': base,
    'note': ParagraphStyle('note', parent=base, textColor=GREY, fontSize=9.5),
    'cell': ParagraphStyle('cell', parent=base, fontSize=9, leading=12.5, spaceAfter=0),
    'cellb': ParagraphStyle('cellb', parent=base, fontName='DV-B', fontSize=9, leading=12.5, spaceAfter=0, textColor=colors.white),
    'li': ParagraphStyle('li', parent=base, spaceAfter=2),
}

def inline(t):
    t = t.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    t = re.sub(r'`([^`]+)`', r'<font face="DVM">\1</font>', t)
    t = re.sub(r'\*\*([^*]+)\*\*', r'<b>\1</b>', t)
    t = re.sub(r'(?<![*\w])\*([^*]+)\*(?!\*)', r'<i>\1</i>', t)
    return t

def table(rows):
    cells = [[c.strip() for c in r.strip().strip('|').split('|')] for r in rows]
    head, body = cells[0], [r for r in cells[2:]]
    ncol = len(head)
    headless = all(not c for c in head)   # "| | |" header: a plain two-column list
    data = [] if headless else [[Paragraph(inline(c), st['cellb']) for c in head]]
    data += [[Paragraph(inline(c), st['cell']) for c in (r + [''] * ncol)[:ncol]] for r in body]
    width = 174 * mm
    if ncol == 2:
        first = max(len(r[0]) for r in cells if r) ; w = [width * (0.30 if first > 12 else 0.22), None]
        w[1] = width - w[0]
    else:
        w = [width * 0.34] + [width * 0.66 / (ncol - 1)] * (ncol - 1)
    t = Table(data, colWidths=w, repeatRows=0 if headless else 1)
    t.setStyle(TableStyle(([] if headless else [('BACKGROUND', (0, 0), (-1, 0), GREEN)]) + [
        ('ROWBACKGROUNDS', (0, 0 if headless else 1), (-1, -1), [colors.white, LIGHT]),
        ('GRID', (0, 0), (-1, -1), 0.4, LINE),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 5), ('RIGHTPADDING', (0, 0), (-1, -1), 5),
        ('TOPPADDING', (0, 0), (-1, -1), 4), ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
    ]))
    return t

def build(md, out):
    lines = md.split('\n'); story = []; i = 0; first_h2 = True
    def bullets(items, numbered):
        flow = [ListItem(Paragraph(inline(x).replace('\x01', '<br/>'), st['li']), leftIndent=14) for x in items]
        return ListFlowable(flow, bulletType='1' if numbered else 'bullet', start='1' if numbered else None,
                            bulletFontName='DV', bulletFontSize=9, leftIndent=14, bulletColor=GREEN)
    while i < len(lines):
        l = lines[i]
        if l.startswith('# '):
            story += [Spacer(1, 30 * mm), Paragraph(inline(l[2:]), st['title'])]; i += 1; continue
        if l.startswith('## '):
            if not first_h2: story.append(PageBreak())
            first_h2 = False
            story.append(Paragraph(inline(l[3:]), st['h2'])); story.append(HRFlowable(width='100%', color=LINE, thickness=0.8, spaceAfter=6)); i += 1; continue
        if l.startswith('### '):
            story.append(Paragraph(inline(l[4:]), st['h3'])); i += 1; continue
        if l.strip() == '---': i += 1; continue
        if l.startswith('|'):
            rows = []
            while i < len(lines) and lines[i].startswith('|'): rows.append(lines[i]); i += 1
            story += [table(rows), Spacer(1, 6)]; continue
        m = re.match(r'^(\s*)([-*]|\d+\.)\s+(.*)', l)
        if m:
            numbered = m.group(2)[0].isdigit(); items = []
            while i < len(lines):
                mm_ = re.match(r'^(\s*)([-*]|\d+\.)\s+(.*)', lines[i])
                if mm_ and (not mm_.group(1) or not items):
                    items.append(mm_.group(3)); i += 1
                elif items and lines[i].startswith('  ') and lines[i].strip():   # sub-bullet or continuation
                    sub = re.sub(r'^\s*[-*]\s+', '– ', lines[i].strip()); items[-1] += '\x01' + sub; i += 1
                elif items and not lines[i].strip() and i + 1 < len(lines) and lines[i + 1].startswith('  ') and lines[i + 1].strip():
                    i += 1   # blank line inside an item (an indented paragraph follows)
                else: break
            story += [bullets(items, numbered), Spacer(1, 4)]; continue
        if not l.strip(): i += 1; continue
        para = [l]; i += 1
        while i < len(lines) and lines[i].strip() and not re.match(r'^(#|\||\s*[-*]\s|\d+\.\s|---)', lines[i]):
            para.append(lines[i]); i += 1
        text = ' '.join(x.strip() for x in para)
        style = st['note'] if text.startswith('*') and text.endswith('*') else st['p']
        story.append(Paragraph(inline(text), style))

    def footer(c, d):
        c.saveState(); c.setFont('DV', 8); c.setFillColor(GREY)
        c.drawString(18 * mm, 10 * mm, 'Car Dash – User Manual'); c.drawRightString(192 * mm, 10 * mm, str(d.page)); c.restoreState()
    doc = SimpleDocTemplate(out, pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=18 * mm, bottomMargin=18 * mm,
                            title='Car Dash – User Manual', author='Car Dash', subject='User manual')
    doc.build(story, onFirstPage=footer, onLaterPages=footer)

build(open(sys.argv[1], encoding='utf-8').read(), sys.argv[2])
