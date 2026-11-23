mod bridge;
mod cli;
mod config;
mod downloader;
mod instance;
mod modrinth;
mod process;
mod server;

use anyhow::{anyhow, Result};
use clap::Parser;
use cli::{Cli, Commands, ConfigAction, InstanceAction, ModAction, VersionAction};
use config::LauncherConfig;
use downloader::Downloader;
use instance::InstanceManager;
use process::ProcessSupervisor;
use server::{run_server, AppState};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "unai_mc_launcher=info,tower_http=info".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    let cli = Cli::parse();

    // 1. Load config
    let mut config = LauncherConfig::load(cli.config.as_deref())?;

    // CLI overrides
    if let Some(dir) = cli.data_dir {
        config.instances_dir = dir.clone();
        config.versions_dir = dir.join("versions");
    }
    if let Some(port) = cli.port {
        config.port = port;
    }

    let instance_mgr = Arc::new(InstanceManager::new(config.instances_dir.clone()));
    let downloader = Arc::new(Downloader::new(config.versions_dir.clone()));
    let modrinth = Arc::new(crate::modrinth::ModrinthClient::new());
    let supervisor = Arc::new(ProcessSupervisor::new());
    let bridge = Arc::new(bridge::BridgeManager::new());

    let state = AppState {
        config: config.clone(),
        instance_mgr: instance_mgr.clone(),
        downloader: downloader.clone(),
        modrinth: modrinth.clone(),
        supervisor: supervisor.clone(),
        bridge: bridge.clone(),
    };

    match cli.command {
        Commands::Daemon(args) => {
            let port = args.port.unwrap_or(config.port);
            let mut daemon_state = state;
            daemon_state.config.port = port;
            run_server(daemon_state, port).await?;
        }
        Commands::Config(args) => match args.action {
            ConfigAction::Show => {
                println!("{}", toml::to_string_pretty(&config)?);
            }
            ConfigAction::Get { key } => match key.as_str() {
                "instances_dir" => println!("{}", config.instances_dir.display()),
                "versions_dir" => println!("{}", config.versions_dir.display()),
                "port" => println!("{}", config.port),
                "default_version" => println!("{}", config.default_version),
                "default_loader" => println!("{}", config.default_loader),
                "default_auth" => println!("{}", config.default_auth),
                other => return Err(anyhow!("Unknown config key '{}'", other)),
            },
            ConfigAction::Set { key, value } => {
                println!("Updated {} = {}", key, value);
                match key.as_str() {
                    "instances_dir" => config.instances_dir = PathBuf::from(&value),
                    "versions_dir" => config.versions_dir = PathBuf::from(&value),
                    "port" => config.port = value.parse()?,
                    "default_version" => config.default_version = value,
                    "default_loader" => config.default_loader = value,
                    "default_auth" => config.default_auth = value,
                    other => return Err(anyhow!("Unknown config key '{}'", other)),
                }
                config.save(cli.config.as_deref())?;
            }
        },
        Commands::Instance(args) => match args.action {
            InstanceAction::List => {
                let list = instance_mgr.list()?;
                if list.is_empty() {
                    println!("No instances found in {:?}", config.instances_dir);
                } else {
                    println!("{:<20} {:<10} {:<10} {:<10}", "NAME", "VERSION", "LOADER", "MEMORY");
                    println!("{:-<55}", "");
                    for inst in list {
                        println!(
                            "{:<20} {:<10} {:<10} {}MB",
                            inst.name, inst.version, inst.loader, inst.memory_mb
                        );
                    }
                }
            }
            InstanceAction::Create {
                name,
                version,
                loader,
                memory,
            } => {
                let ver = version.unwrap_or(config.default_version);
                let ldr = loader.unwrap_or(config.default_loader);
                let inst = instance_mgr.create(&name, &ver, &ldr, memory)?;
                println!("Successfully created instance '{}' ({}, {})", inst.name, inst.version, inst.loader);
            }
            InstanceAction::Delete { name } => {
                instance_mgr.delete(&name)?;
                println!("Deleted instance '{}'", name);
            }
            InstanceAction::Launch {
                name,
                headless,
                username,
                auth,
                memory,
            } => {
                let inst = instance_mgr.get(&name)?;
                let uname = username.unwrap_or_else(|| "DiromGPT".to_string());
                let atype = auth.unwrap_or(config.default_auth);
                let mem = memory.unwrap_or(inst.memory_mb);
                let session_token = uuid::Uuid::new_v4().to_string();
                let authlib_path = config.versions_dir.join("authlib-injector.jar");

                let pid = supervisor
                    .launch(
                        &name,
                        &instance_mgr.instance_dir(&name),
                        &inst.version,
                        &inst.loader,
                        mem,
                        config.port,
                        &session_token,
                        &uname,
                        &atype,
                        headless,
                        if authlib_path.exists() { Some(&authlib_path) } else { None },
                        &config.versions_dir,
                        inst.quick_play_world.as_deref(),
                    )
                    .await?;
                println!("Launched instance '{}' with PID {} (headless: {})", name, pid, headless);
            }
            InstanceAction::Stop => {
                supervisor.stop().await?;
                println!("Stopped running game process");
            }
            InstanceAction::Configure { name, settings } => {
                let mut map = HashMap::new();
                for s in settings {
                    if let Some((k, v)) = s.split_once('=') {
                        map.insert(k.trim().to_string(), v.trim().to_string());
                    } else {
                        println!("Skipping invalid setting '{}' (expected key=value)", s);
                    }
                }
                instance_mgr.configure_options(&name, &map)?;
                println!("Updated options for instance '{}'", name);
            }
        },
        Commands::Mod(args) => match args.action {
            ModAction::Install { instance, source } => {
                let inst = instance_mgr.get(&instance)?;
                let p = PathBuf::from(&source);
                if p.exists() && p.is_file() {
                    let target = instance_mgr.install_mod_file(&instance, &p)?;
                    println!("Installed local mod to {:?}", target);
                } else {
                    let mods_dir = instance_mgr.mods_dir(&instance);
                    let target = modrinth
                        .resolve_and_download(&source, &inst.loader, &inst.version, &mods_dir)
                        .await?;
                    println!("Installed mod from Modrinth to {:?}", target);
                }
            }
            ModAction::List { instance } => {
                let mods = instance_mgr.list_mods_detailed(&instance)?;
                if mods.is_empty() {
                    println!("No mods installed in instance '{}'", instance);
                } else {
                    println!("{:<40} {:<10} {:<10}", "MOD FILE", "STATUS", "SIZE");
                    println!("{:-<62}", "");
                    for m in mods {
                        let status_str = if m.enabled { "ENABLED" } else { "DISABLED" };
                        let size_kb = m.size_bytes / 1024;
                        println!("{:<40} {:<10} {} KB", m.filename, status_str, size_kb);
                    }
                }
            }
            ModAction::Remove { instance, filename } => {
                instance_mgr.remove_mod(&instance, &filename)?;
                println!("Removed mod '{}' from instance '{}'", filename, instance);
            }
            ModAction::Enable { instance, filename } => {
                let new_name = instance_mgr.toggle_mod(&instance, &filename, true)?;
                println!("Enabled mod: {}", new_name);
            }
            ModAction::Disable { instance, filename } => {
                let new_name = instance_mgr.toggle_mod(&instance, &filename, false)?;
                println!("Disabled mod: {}", new_name);
            }
            ModAction::Search { query, loader, version } => {
                let hits = modrinth.search(&query, &loader, &version).await?;
                if hits.is_empty() {
                    println!("No mods found for query '{}'", query);
                } else {
                    println!("Found {} mods on Modrinth:", hits.len());
                    for h in hits {
                        println!(" - {} ({}) : {}", h.title, h.slug, h.description);
                    }
                }
            }
        },
        Commands::Version(args) => match args.action {
            VersionAction::Install { version, loader } => {
                let p = downloader.install_version(&version, &loader).await?;
                println!("Installed version {} ({}) at {:?}", version, loader, p);
            }
        },
    }

    Ok(())
}
