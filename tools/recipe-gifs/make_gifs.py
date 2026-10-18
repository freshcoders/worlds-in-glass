"""Draws the mod's crafting recipes as crafting-table GIFs for the website, from the item icons the game saved.

    gradlew runClient -PexportIcons=<icons folder>     (with items.txt from here copied into that folder first)
    python tools/recipe-gifs/make_gifs.py <icons folder>

Writes site/img/recipes/<recipe>.gif. Ingredients that can be any of a kind (any wooden slab, any boat) take turns,
like in the recipe book; globes.gif goes through every globe recipe in turn.
"""
import json, os, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RECIPES = os.path.join(ROOT, "src", "main", "resources", "data", "worldsinglass", "recipe")
OUT = os.path.join(ROOT, "site", "img", "recipes")
ICONS = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "recipe-icons")

S = 4                      # screen pixels per GUI pixel
WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped"]
TAGS = {
    "minecraft:wooden_slabs": [f"minecraft:{w}_slab" for w in WOODS],
    "minecraft:boats": [f"minecraft:{w}_boat" for w in WOODS[:8]] + ["minecraft:bamboo_raft"],
}
GLOBES = ["snow_globe", "terrarium", "storm_globe", "ship_bottle", "desert_globe", "aquarium", "star_globe", "nether_jar"]
FRAME_MS = 1000

# The crafting table screen's colours.
PANEL, LIGHT, DARK, SLOT = (198, 198, 198), (255, 255, 255), (85, 85, 85), (139, 139, 139)
SLOT_DARK, SLOT_LIGHT = (55, 55, 55), (255, 255, 255)

_icons = {}


def icon(item, size=16):
    key = (item, size)
    if key not in _icons:
        ns, path = item.split(":")
        img = Image.open(os.path.join(ICONS, f"{ns}_{path}.png")).convert("RGB")
        _icons[key] = img.resize((size * S, size * S), Image.LANCZOS)
    return _icons[key]


def choices(ingredient):
    if "item" in ingredient:
        return [ingredient["item"]]
    return TAGS[ingredient["tag"]]


def slot(draw, x, y, size=18):
    """A sunken slot whose outer corner is at GUI pixel (x, y)."""
    draw.rectangle([x * S, y * S, (x + size) * S - 1, (y + size) * S - 1], fill=SLOT)
    draw.rectangle([x * S, y * S, (x + size - 1) * S - 1, (y + 1) * S - 1], fill=SLOT_DARK)
    draw.rectangle([x * S, y * S, (x + 1) * S - 1, (y + size - 1) * S - 1], fill=SLOT_DARK)
    draw.rectangle([(x + 1) * S, (y + size - 1) * S, (x + size) * S - 1, (y + size) * S - 1], fill=SLOT_LIGHT)
    draw.rectangle([(x + size - 1) * S, (y + 1) * S, (x + size) * S - 1, (y + size) * S - 1], fill=SLOT_LIGHT)


W, H = 124, 68             # the panel in GUI pixels
GRID_X, GRID_Y = 7, 7
OUT_X, OUT_Y = 94, 21      # the result slot (26 wide)
ARROW_X, ARROW_Y = 66, 27


def background():
    img = Image.new("RGB", (W * S, H * S), PANEL)
    d = ImageDraw.Draw(img)
    # Raised edge of the panel.
    d.rectangle([0, 0, W * S - 1, S - 1], fill=LIGHT)
    d.rectangle([0, 0, S - 1, H * S - 1], fill=LIGHT)
    d.rectangle([0, (H - 1) * S, W * S - 1, H * S - 1], fill=DARK)
    d.rectangle([(W - 1) * S, 0, W * S - 1, H * S - 1], fill=DARK)
    for r in range(3):
        for c in range(3):
            slot(d, GRID_X + c * 18, GRID_Y + r * 18)
    slot(d, OUT_X, OUT_Y, 26)
    # The arrow: a shaft and a head, in the screen's dark grey.
    d.rectangle([ARROW_X * S, (ARROW_Y + 5) * S, (ARROW_X + 15) * S - 1, (ARROW_Y + 9) * S - 1], fill=SLOT)
    d.polygon([((ARROW_X + 14) * S, (ARROW_Y + 0) * S), ((ARROW_X + 22) * S, (ARROW_Y + 7) * S), ((ARROW_X + 14) * S, (ARROW_Y + 14) * S)], fill=SLOT)
    return img


FONT = ImageFont.truetype("arialbd.ttf", 9 * S) if os.name == "nt" else ImageFont.load_default()


def frame(grid, result, count):
    """grid: 9 item ids or None, row by row."""
    img = background()
    for i, item in enumerate(grid):
        if item:
            img.paste(icon(item), ((GRID_X + 1 + (i % 3) * 18) * S, (GRID_Y + 1 + (i // 3) * 18) * S))
    img.paste(icon(result), ((OUT_X + 5) * S, (OUT_Y + 5) * S))
    if count > 1:
        d = ImageDraw.Draw(img)
        x, y = (OUT_X + 22) * S, (OUT_Y + 22) * S
        d.text((x + S, y + S), str(count), font=FONT, fill=(63, 63, 63), anchor="rb")
        d.text((x, y), str(count), font=FONT, fill=(255, 255, 255), anchor="rb")
    return img


def layouts(recipe):
    """Every frame's grid for a recipe, with each multi-choice ingredient taking its turns together."""
    kind = recipe["type"]
    cells = [None] * 9
    if kind == "minecraft:crafting_shaped":
        rows = recipe["pattern"]
        width = max(len(r) for r in rows)
        left = (3 - width) // 2
        top = (3 - len(rows)) // 2
        for r, row in enumerate(rows):
            for c, ch in enumerate(row):
                if ch != " ":
                    cells[(top + r) * 3 + left + c] = choices(recipe["key"][ch])
    else:
        for i, ing in enumerate(recipe["ingredients"]):
            cells[i] = choices(ing)
    turns = max((len(c) for c in cells if c), default=1)
    return [[c[t % len(c)] if c else None for c in cells] for t in range(turns)]


def save(name, frames):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".gif")
    frames = [f.convert("P", palette=Image.ADAPTIVE, colors=255) for f in frames]
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=FRAME_MS, loop=0, optimize=True)
    print(f"{name}.gif: {len(frames)} frame(s), {os.path.getsize(path) // 1024} KB")


def main():
    every_globe = []
    for file in sorted(os.listdir(RECIPES)):
        recipe = json.load(open(os.path.join(RECIPES, file), encoding="utf-8"))
        name = file[:-5]
        if recipe["type"] == "worldsinglass:twin_globe":
            # A globe with a world in it, an empty one of the same kind and an ender pearl: two of them back.
            frames = [frame([f"worldsinglass:{g}", f"worldsinglass:{g}", "minecraft:ender_pearl"] + [None] * 6, f"worldsinglass:{g}", 2)
                      for g in GLOBES]
        else:
            result = recipe["result"]
            frames = [frame(grid, result["id"], result.get("count", 1)) for grid in layouts(recipe)]
            if name in GLOBES:
                every_globe.append(frames[0])
        save(name, frames)
    save("globes", every_globe)


if __name__ == "__main__":
    main()
