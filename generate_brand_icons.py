import os
from PIL import Image, ImageDraw

def render_eye_logo(size=512, bg_color='#FEFAF3', stroke_color='#1E2B1E', fill_pupil=True, round_bg=False):
    scale = 4
    s = size * scale
    im = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)

    if round_bg:
        # Full circle background
        draw.ellipse([0, 0, s-1, s-1], fill=bg_color)
    elif bg_color:
        # Squircle / rounded rectangle
        r = int(s * 0.22)
        draw.rounded_rectangle([0, 0, s-1, s-1], radius=r, fill=bg_color)

    # Coords from 100x100 viewBox with padding
    pad = s * 0.14
    dim = s * 0.72

    def tx(x, y):
        return (pad + (x / 100.0) * dim, pad + (y / 100.0) * dim)

    # 1. Slanted Bold Visor / Eyebrow Bar: polygon points="20,26 80,51 77,55 19,30"
    visor_pts = [tx(19, 25), tx(81, 50.5), tx(78, 55.5), tx(18, 30)]
    draw.polygon(visor_pts, fill=stroke_color)

    # 2. Eye shape outline:
    # M 17 38 C 30 42, 54 48, 72 51 C 69 57, 69 63, 72 68 C 50 63, 30 54, 17 38 Z
    def cubic_bezier(p0, p1, p2, p3, steps=60):
        pts = []
        for i in range(steps + 1):
            t = i / steps
            x = (1-t)**3 * p0[0] + 3*(1-t)**2*t * p1[0] + 3*(1-t)*t**2 * p2[0] + t**3 * p3[0]
            y = (1-t)**3 * p0[1] + 3*(1-t)**2*t * p1[1] + 3*(1-t)*t**2 * p2[1] + t**3 * p3[1]
            pts.append((x, y))
        return pts

    c1 = cubic_bezier(tx(17, 38), tx(30, 42), tx(54, 48), tx(72, 51), steps=60)
    c2 = cubic_bezier(tx(72, 51), tx(69, 57), tx(69, 63), tx(72, 68), steps=30)
    c3 = cubic_bezier(tx(72, 68), tx(50, 63), tx(30, 54), tx(17, 38), steps=60)
    eye_contour = c1 + c2[1:] + c3[1:]

    line_w = int(dim * 0.038)

    # Draw eye contour line
    for i in range(len(eye_contour) - 1):
        draw.line([eye_contour[i], eye_contour[i+1]], fill=stroke_color, width=line_w)
    # Join end to start
    draw.line([eye_contour[-1], eye_contour[0]], fill=stroke_color, width=line_w)

    # 3. Inner socket line: d="M 47 45 C 47 56, 54 62, 66 61"
    s_pts = cubic_bezier(tx(47, 45), tx(47, 56), tx(54, 62), tx(66, 61), steps=40)
    for i in range(len(s_pts) - 1):
        draw.line([s_pts[i], s_pts[i+1]], fill=stroke_color, width=int(line_w * 0.85))

    # 4. Circular Pupil: circle cx="54" cy="53" r="6.2"
    p_center = tx(54, 53)
    p_r = dim * 0.075
    draw.ellipse([p_center[0]-p_r, p_center[1]-p_r, p_center[0]+p_r, p_center[1]+p_r], fill=stroke_color)

    # 5. Specular Glint: circle cx="56.2" cy="51.2" r="2.2"
    g_center = tx(56.2, 51.2)
    g_r = dim * 0.026
    glint_color = bg_color if bg_color else '#FFFFFF'
    draw.ellipse([g_center[0]-g_r, g_center[1]-g_r, g_center[0]+g_r, g_center[1]+g_r], fill=glint_color)

    return im.resize((size, size), Image.Resampling.LANCZOS)

res_dir = r'android_studio_project\app\src\main\res'

# 1. Main app logo drawable
logo = render_eye_logo(512, bg_color='#FEFAF3', stroke_color='#1E2B1E', round_bg=False)
logo.save(os.path.join(res_dir, 'drawable', 'feedtrainer_logo.png'))
print('Saved feedtrainer_logo.png')

# 2. Launcher icons (square and round)
density_sizes = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

for folder, sz in density_sizes.items():
    fpath = os.path.join(res_dir, folder)
    os.makedirs(fpath, exist_ok=True)

    # Standard launcher icon (squircle / square)
    sq_icon = render_eye_logo(sz, bg_color='#FEFAF3', stroke_color='#1E2B1E', round_bg=False)
    sq_icon.save(os.path.join(fpath, 'ic_launcher.png'))

    # Round launcher icon
    rd_icon = render_eye_logo(sz, bg_color='#FEFAF3', stroke_color='#1E2B1E', round_bg=True)
    rd_icon.save(os.path.join(fpath, 'ic_launcher_round.png'))
    print(f'Saved {folder} ({sz}x{sz})')

# 3. Transparent foreground icon for adaptive icon / widget
fg_icon = render_eye_logo(432, bg_color=None, stroke_color='#1E2B1E', round_bg=False)
fg_icon.save(os.path.join(res_dir, 'drawable', 'ic_launcher_foreground.png'))
print('Saved ic_launcher_foreground.png')
