from workspace import MinecraftWorkspace


def register(kernel):
    """Register Minecraft Workspace in the microkernel."""
    ws = MinecraftWorkspace(runtime_id="minecraft", bus=kernel._bus)
    kernel._manifest_registry["minecraft"] = {
        "manifest": ws.manifest,
        "capabilities": [],
        "behaviors": [],
        "features": ws.manifest.features,
    }
    return ws


def install(ctx):
    print("Installing Minecraft Workspace...")


def start(ctx):
    print("Starting Minecraft Workspace...")


def stop(ctx):
    print("Stopping Minecraft Workspace...")


def uninstall(ctx):
    print("Uninstalling Minecraft Workspace...")
