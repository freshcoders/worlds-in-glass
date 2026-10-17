"""Puts Worlds in Glass on Modrinth, using your personal access token (from ~/.modrinth-token or MODRINTH_TOKEN).

    python modrinth/publish.py create    # a draft project (only you can see it): icon, description, gallery, version 1.0.0
    python modrinth/publish.py version   # upload build/libs/worldsinglass-<version>.jar as a new version of the project
    python modrinth/publish.py replace   # delete the version with this number and upload the current jar in its place
    python modrinth/publish.py rename    # bring the project's name, link (slug), summary and description up to date
    python modrinth/publish.py submit    # send the draft to Modrinth's moderators for review (then it goes public)

The token never appears in output. Token scopes needed: Create projects, Write projects, Create versions.
"""
import json, os, re, sys
import requests

API = "https://api.modrinth.com/v2"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SLUG = "worlds-in-glass"
TITLE = "Worlds in Glass"
SUMMARY = "Build tiny worlds inside snow globes, terrariums, jars and bottles. Step inside to build, step out to see it in miniature."
CREATE = "LNytGWDc"  # Create's project id, an optional dependency
GALLERY = [
    ("gallery.jpg", "A collector's gallery", True),
    ("nether-jar.jpg", "Nether jar", False),
    ("desert-globe.jpg", "Desert globe", False),
    ("storm-globe.jpg", "Storm globe", False),
    ("star-globe-and-nether-jar.jpg", "Star globe and nether jar on a collector's shelf", False),
    ("inside-desert-globe.jpg", "From inside: the room outside, giant", False),
    ("create-helicopter.jpg", "A Create schematic built inside a globe", False),
    ("gallery-shelves.jpg", "Collector's shelves", False),
]


TOKEN_CACHE = []


def token():
    """From MODRINTH_TOKEN, or else the file ~/.modrinth-token (handy on Windows, where a new variable only reaches new programs)."""
    if TOKEN_CACHE:
        return TOKEN_CACHE[0]
    value = os.environ.get("MODRINTH_TOKEN", "").strip()
    path = os.path.join(os.path.expanduser("~"), ".modrinth-token")
    if not value and os.path.exists(path):
        value = open(path, encoding="utf-8").read().strip()
    if not value and sys.stdin.isatty():
        # Typed in once (not shown on screen), then kept in ~/.modrinth-token for next time.
        import getpass
        value = getpass.getpass("Modrinth token (mrp_..., not shown as you type): ").strip()
        if value:
            with open(path, "w", encoding="utf-8") as saved:
                saved.write(value)
            print(f"Saved in {path} for next time.")
    if not value:
        sys.exit("No token: create one at https://modrinth.com/settings/pats, then run this in a terminal and paste it when asked.")
    TOKEN_CACHE.append(value)
    return value


def headers():
    return {"Authorization": token(), "User-Agent": "ikdekker/worlds-in-glass publish script"}


def mod_version():
    props = open(os.path.join(ROOT, "gradle.properties"), encoding="utf-8").read()
    return re.search(r"^mod_version=(.+)$", props, re.M).group(1).strip()


def check(response, what):
    if not response.ok:
        sys.exit(f"{what} failed: {response.status_code} {response.text[:500]}")
    print(f"{what}: ok")
    return response.json() if response.text else None


def create():
    body = open(os.path.join(HERE, "description.md"), encoding="utf-8").read()
    data = {
        "slug": SLUG,
        "title": TITLE,
        "description": SUMMARY,
        "categories": ["decoration", "adventure"],
        "additional_categories": ["magic"],
        "client_side": "required",
        "server_side": "required",
        "body": body,
        "license_id": "MIT",
        "project_type": "mod",
        "is_draft": True,
        "initial_versions": [],
    }
    with open(os.path.join(HERE, "icon.png"), "rb") as icon:
        project = check(requests.post(f"{API}/project", headers=headers(),
                                      files={"data": (None, json.dumps(data), "application/json"),
                                             "icon": ("icon.png", icon, "image/png")}), "Create project")
    print(f"Project id {project['id']}: https://modrinth.com/mod/{SLUG}")
    for i, (name, title, featured) in enumerate(GALLERY):
        with open(os.path.join(HERE, name), "rb") as image:
            check(requests.post(f"{API}/project/{SLUG}/gallery", headers=headers(),
                                params={"ext": "jpg", "featured": str(featured).lower(), "title": title, "ordering": i},
                                data=image.read()), f"Gallery: {title}")
    version()


def version():
    number = mod_version()
    jar = os.path.join(ROOT, "build", "libs", f"worldsinglass-{number}.jar")
    if not os.path.exists(jar):
        sys.exit(f"Build the jar first: {jar} is missing.")
    project = check(requests.get(f"{API}/project/{SLUG}", headers=headers()), "Find project")
    data = {
        "name": f"{TITLE} {number}",
        "version_number": number,
        "changelog": open(os.path.join(HERE, "changelog.md"), encoding="utf-8").read(),
        "dependencies": [{"project_id": CREATE, "dependency_type": "optional"}],
        "game_versions": ["1.21.1"],
        "version_type": "release",
        "loaders": ["neoforge"],
        "featured": True,
        "project_id": project["id"],
        "file_parts": ["jar"],
        "primary_file": "jar",
    }
    with open(jar, "rb") as file:
        check(requests.post(f"{API}/version", headers=headers(),
                            files={"data": (None, json.dumps(data), "application/json"),
                                   "jar": (os.path.basename(jar), file, "application/java-archive")}), f"Upload {number}")


def replace():
    """Takes down the version with the current number (still in draft, so nobody has it) and uploads the jar again."""
    number = mod_version()
    versions = check(requests.get(f"{API}/project/{SLUG}/version", headers=headers()), "List versions")
    for v in versions:
        if v["version_number"] == number:
            check(requests.delete(f"{API}/version/{v['id']}", headers=headers()), f"Delete old {number}")
    version()


def rename(old="snow-globes"):
    """Gives the project its current title, slug, summary and description (found by its old slug if it still has it)."""
    found = requests.get(f"{API}/project/{SLUG}", headers=headers())
    current = SLUG if found.ok else old
    body = open(os.path.join(HERE, "description.md"), encoding="utf-8").read()
    fields = {"title": TITLE, "description": SUMMARY, "body": body}
    if current != SLUG:
        fields["slug"] = SLUG  # Modrinth refuses a slug the project already has
    check(requests.patch(f"{API}/project/{current}", headers=headers(), json=fields), "Rename project")
    for v in check(requests.get(f"{API}/project/{SLUG}/version", headers=headers()), "List versions"):
        check(requests.patch(f"{API}/version/{v['id']}", headers=headers(), json={"name": f"{TITLE} {v['version_number']}"}),
              f"Rename version {v['version_number']}")
    print(f"https://modrinth.com/mod/{SLUG}")


def submit():
    check(requests.patch(f"{API}/project/{SLUG}", headers=headers(), json={"status": "processing"}), "Submit for review")


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else ""
    {"create": create, "version": version, "replace": replace, "rename": rename, "submit": submit}.get(action, lambda: sys.exit(__doc__))()
