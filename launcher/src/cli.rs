use clap::{Args, Parser, Subcommand};
use std::path::PathBuf;

#[derive(Parser, Debug)]
#[command(
    name = "unai-mc-launcher",
    author = "KasperStudios",
    version = env!("CARGO_PKG_VERSION"),
    about = "Autonomous Standalone Minecraft Launcher & Reverse RPC Bridge for AI Agents"
)]
pub struct Cli {
    /// Custom configuration file path
    #[arg(short, long, global = true)]
    pub config: Option<PathBuf>,

    /// Override instances directory
    #[arg(short, long, global = true)]
    pub data_dir: Option<PathBuf>,

    /// Override bridge port
    #[arg(short, long, global = true)]
    pub port: Option<u16>,

    #[command(subcommand)]
    pub command: Commands,
}

#[derive(Subcommand, Debug)]
pub enum Commands {
    /// Run the background HTTP REST & Reverse WebSocket Daemon
    Daemon(DaemonArgs),

    /// Manage launcher configuration
    Config(ConfigArgs),

    /// Manage game instances
    Instance(InstanceArgs),

    /// Manage mods in instances
    Mod(ModArgs),

    /// Download and manage Minecraft & Loader versions
    Version(VersionArgs),
}

#[derive(Args, Debug)]
pub struct DaemonArgs {
    /// Custom port to bind the daemon on
    #[arg(short, long)]
    pub port: Option<u16>,
}

#[derive(Args, Debug)]
pub struct ConfigArgs {
    #[command(subcommand)]
    pub action: ConfigAction,
}

#[derive(Subcommand, Debug)]
pub enum ConfigAction {
    /// Show full active configuration
    Show,
    /// Get specific config field
    Get { key: String },
    /// Set specific config field (instances_dir, versions_dir, port, default_version, default_loader, default_auth)
    Set { key: String, value: String },
}

#[derive(Args, Debug)]
pub struct InstanceArgs {
    #[command(subcommand)]
    pub action: InstanceAction,
}

#[derive(Subcommand, Debug)]
pub enum InstanceAction {
    /// List all created instances
    List,
    /// Create a new game instance
    Create {
        name: String,
        #[arg(short, long)]
        version: Option<String>,
        #[arg(short, long)]
        loader: Option<String>,
        #[arg(short, long)]
        memory: Option<u32>,
    },
    /// Delete an instance
    Delete { name: String },
    /// Launch an instance
    Launch {
        name: String,
        #[arg(long)]
        headless: bool,
        #[arg(short, long)]
        username: Option<String>,
        #[arg(short, long)]
        auth: Option<String>,
        #[arg(short, long)]
        memory: Option<u32>,
    },
    /// Stop currently running game instance
    Stop,
    /// Configure options.txt settings (e.g. renderDistance=4 maxFps=60)
    Configure {
        name: String,
        #[arg(required = true)]
        settings: Vec<String>,
    },
}

#[derive(Args, Debug)]
pub struct ModArgs {
    #[command(subcommand)]
    pub action: ModAction,
}

#[derive(Subcommand, Debug)]
pub enum ModAction {
    /// Install a mod into an instance (from Modrinth query/slug, HTTP URL, or local file)
    Install {
        instance: String,
        /// Mod name/slug on Modrinth (e.g. sodium, lithium), direct URL, or local .jar path
        source: String,
    },
    /// List mods installed in an instance
    List { instance: String },
    /// Remove a mod from an instance
    Remove { instance: String, filename: String },
    /// Enable a disabled mod (.jar.disabled -> .jar)
    Enable { instance: String, filename: String },
    /// Disable a mod (.jar -> .jar.disabled)
    Disable { instance: String, filename: String },
    /// Search mods on Modrinth
    Search {
        query: String,
        #[arg(short, long, default_value = "fabric")]
        loader: String,
        #[arg(short, long, default_value = "1.21.1")]
        version: String,
    },
}

#[derive(Args, Debug)]
pub struct VersionArgs {
    #[command(subcommand)]
    pub action: VersionAction,
}

#[derive(Subcommand, Debug)]
pub enum VersionAction {
    /// Download and prepare a Minecraft version
    Install {
        version: String,
        #[arg(short, long, default_value = "fabric")]
        loader: String,
    },
}
