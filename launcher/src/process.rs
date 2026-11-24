use anyhow::{Context, Result};
use std::collections::HashSet;
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use tokio::sync::Mutex;
use tracing::{info, warn};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProcessStatus {
    Idle,
    #[allow(dead_code)]
    Starting,
    Running { pid: u32 },
    Exited { code: Option<i32> },
}

pub struct ActiveProcess {
    pub instance_name: String,
    pub pid: u32,
    #[allow(dead_code)]
    pub session_token: String,
    pub child: Child,
}

pub struct ProcessSupervisor {
    current: Arc<Mutex<Option<ActiveProcess>>>,
    #[allow(dead_code)]
    pub is_terminating: Arc<AtomicBool>,
}

impl ProcessSupervisor {
    pub fn new() -> Self {
        Self {
            current: Arc::new(Mutex::new(None)),
            is_terminating: Arc::new(AtomicBool::new(false)),
        }
    }

    pub async fn get_status(&self) -> ProcessStatus {
        let mut lk = self.current.lock().await;
        if let Some(proc) = lk.as_mut() {
            match proc.child.try_wait() {
                Ok(Some(status)) => {
                    let code = status.code();
                    let pid = proc.pid;
                    let name = proc.instance_name.clone();
                    *lk = None;
                    info!("[Process] Game process {} ({}) exited with code {:?}", name, pid, code);
                    ProcessStatus::Exited { code }
                }
                Ok(None) => ProcessStatus::Running { pid: proc.pid },
                Err(e) => {
                    warn!("[Process] Failed to poll child process: {}", e);
                    ProcessStatus::Running { pid: proc.pid }
                }
            }
        } else {
            ProcessStatus::Idle
        }
    }

    pub fn find_java_binary(_game_version: &str) -> Result<PathBuf> {
        let exe_name = if cfg!(windows) { "java.exe" } else { "java" };

        // 1. Check JAVA_HOME
        if let Ok(jh) = std::env::var("JAVA_HOME") {
            let bin = PathBuf::from(jh).join("bin").join(exe_name);
            if bin.exists() {
                return Ok(bin);
            }
        }

        // 2. Windows-specific standard JDK locations
        if cfg!(windows) {
            let program_files = std::env::var("ProgramFiles").unwrap_or_else(|_| "C:\\Program Files".to_string());
            for vendor in &["Java", "Eclipse Adoptium", "Microsoft", "BellSoft", "Amazon Corretto", "Zulu"] {
                let base = PathBuf::from(&program_files).join(vendor);
                if let Ok(entries) = fs::read_dir(&base) {
                    for entry in entries.flatten() {
                        let p = entry.path().join("bin").join("java.exe");
                        if p.exists() {
                            return Ok(p);
                        }
                    }
                }
            }
            return Ok(PathBuf::from(exe_name));
        }

        // 3. Linux-specific openjdk paths (prefer Java 21)
        let j21_ubuntu = PathBuf::from("/usr/lib/jvm/java-21-openjdk-amd64/bin/java");
        if j21_ubuntu.exists() {
            return Ok(j21_ubuntu);
        }
        let j21_arch = PathBuf::from("/usr/lib/jvm/java-21-openjdk/bin/java");
        if j21_arch.exists() {
            return Ok(j21_arch);
        }
        let j17 = PathBuf::from("/usr/lib/jvm/java-17-openjdk-amd64/bin/java");
        if j17.exists() {
            return Ok(j17);
        }
        let j17_arch = PathBuf::from("/usr/lib/jvm/java-17-openjdk/bin/java");
        if j17_arch.exists() {
            return Ok(j17_arch);
        }

        Ok(PathBuf::from(exe_name))
    }

    pub async fn launch(
        &self,
        instance_name: &str,
        instance_dir: &Path,
        game_version: &str,
        loader: &str,
        memory_mb: u32,
        port: u16,
        session_token: &str,
        username: &str,
        auth_type: &str,
        headless: bool,
        authlib_path: Option<&Path>,
        versions_dir: &Path,
        quick_play_world: Option<&str>,
    ) -> Result<u32> {
        self.stop().await?;

        let java_bin = Self::find_java_binary(game_version)?;
        info!("[Process] Using Java binary: {:?}", java_bin);

        let logs_dir = instance_dir.join("logs");
        fs::create_dir_all(&logs_dir)?;
        let log_file = File::create(logs_dir.join("unai_launcher.log"))
            .context("Failed to create log file")?;

        let ws_url = format!("ws://127.0.0.1:{}/game_bridge", port);

        let mut cmd = if headless {
            if which_tool("xvfb-run") {
                let mut c = Command::new("xvfb-run");
                c.args(["-a", "-s", "-screen 0 800x600x24"]);
                c.arg(java_bin);
                c
            } else {
                warn!("[Process] xvfb-run not found! Falling back to standard display launch");
                Command::new(java_bin)
            }
        } else {
            let mut c = Command::new(java_bin);
            // Ensure GUI environment variables are inherited or set on Linux
            if !cfg!(windows) {
                if std::env::var("DISPLAY").is_err() {
                    c.env("DISPLAY", ":0");
                }
                if std::env::var("WAYLAND_DISPLAY").is_err() {
                    c.env("WAYLAND_DISPLAY", "wayland-1");
                }
            }
            c
        };

        // JVM arguments
        cmd.arg(format!("-Xmx{}M", memory_mb));
        cmd.arg("-Xms512M");
        cmd.arg(format!("-Dunai.ws={}", ws_url));
        cmd.arg(format!("-Dunai.token={}", session_token));

        // Ely.by support
        if auth_type.eq_ignore_ascii_case("elyby") {
            if let Some(ap) = authlib_path {
                if ap.exists() {
                    cmd.arg(format!("-javaagent:{}={}", ap.display(), "https://auth.ely.by"));
                    info!("[Process] Enabled Ely.by skin authlib-injector agent");
                }
            }
        }

        // Build classpath and select mainClass dynamically
        let ver_dir = versions_dir.join(game_version);
        let mut classpath_entries: Vec<String> = Vec::new();
        let mut main_class = "net.minecraft.client.main.Main".to_string();

        let ldr = loader.to_lowercase();
        if ldr == "fabric" || ldr == "quilt" {
            // Find fabric profile json
            let mut fabric_json_opt = None;
            if ver_dir.exists() {
                for entry in fs::read_dir(&ver_dir)? {
                    let entry = entry?;
                    let fname = entry.file_name().to_string_lossy().to_string();
                    if fname.contains("fabric") && fname.ends_with(".json") {
                        fabric_json_opt = Some(entry.path());
                        break;
                    }
                }
            }

            if let Some(fab_json_path) = fabric_json_opt {
                info!("[Process] Found Fabric profile JSON: {:?}", fab_json_path);
                let content = fs::read_to_string(&fab_json_path)?;
                let profile: serde_json::Value = serde_json::from_str(&content)?;

                if let Some(mc) = profile.get("mainClass").and_then(|v| v.as_str()) {
                    main_class = mc.to_string();
                }

                if let Some(jvm_args) = profile.pointer("/arguments/jvm").and_then(|v| v.as_array()) {
                    for arg in jvm_args {
                        if let Some(s) = arg.as_str() {
                            cmd.arg(s.trim());
                        }
                    }
                }
            } else {
                main_class = "net.fabricmc.loader.impl.launch.knot.KnotClient".to_string();
            }
        }

        // Scan all libraries from versions_dir and instance libraries
        let mut cp_set = HashSet::new();

        // 1. Client jar
        let client_jar = ver_dir.join(format!("{}.jar", game_version));
        if client_jar.exists() {
            cp_set.insert(client_jar.to_string_lossy().to_string());
        }

        // 2. Exact libraries declared in version JSON
        let version_json_path = ver_dir.join(format!("{}.json", game_version));
        if version_json_path.exists() {
            if let Ok(content) = fs::read_to_string(&version_json_path) {
                if let Ok(vjson) = serde_json::from_str::<serde_json::Value>(&content) {
                    if let Some(libs) = vjson.get("libraries").and_then(|v| v.as_array()) {
                        for lib in libs {
                            if let Some(art) = lib.pointer("/downloads/artifact") {
                                if let Some(rel) = art.get("path").and_then(|v| v.as_str()) {
                                    let p = versions_dir.join("libraries").join(rel);
                                    if p.exists() {
                                        cp_set.insert(p.to_string_lossy().to_string());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Exact libraries declared in Fabric profile JSON
        let mut fabric_json_opt = None;
        if ver_dir.exists() {
            for entry in fs::read_dir(&ver_dir)? {
                let entry = entry?;
                let fname = entry.file_name().to_string_lossy().to_string();
                if fname.contains("fabric") && fname.ends_with(".json") {
                    fabric_json_opt = Some(entry.path());
                    break;
                }
            }
        }

        if let Some(fab_json_path) = &fabric_json_opt {
            if let Ok(content) = fs::read_to_string(fab_json_path) {
                if let Ok(fjson) = serde_json::from_str::<serde_json::Value>(&content) {
                    if let Some(libs) = fjson.get("libraries").and_then(|v| v.as_array()) {
                        for lib in libs {
                            if let Some(name) = lib.get("name").and_then(|v| v.as_str()) {
                                let parts: Vec<&str> = name.split(':').collect();
                                if parts.len() >= 3 {
                                    let group_path = parts[0].replace('.', "/");
                                    let artifact = parts[1];
                                    let ver = parts[2];
                                    let jar_name = format!("{}-{}.jar", artifact, ver);
                                    let rel_path = format!("{}/{}/{}/{}", group_path, artifact, ver, jar_name);
                                    let p = versions_dir.join("libraries").join(rel_path);
                                    if p.exists() {
                                        cp_set.insert(p.to_string_lossy().to_string());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Instance libraries/
        let inst_libs_dir = instance_dir.join("libraries");
        if inst_libs_dir.exists() {
            collect_jars(&inst_libs_dir, &mut cp_set);
        }

        classpath_entries.extend(cp_set);

        let cp_sep = if cfg!(windows) { ";" } else { ":" };
        let full_classpath = classpath_entries.join(cp_sep);
        cmd.arg("-cp").arg(full_classpath);
        info!("[Process] Selected Main Class: {}", main_class);
        cmd.arg(main_class);

        // Offline UUID generation
        let offline_uuid = uuid::Uuid::new_v3(&uuid::Uuid::NAMESPACE_DNS, username.as_bytes());

        // Working directory & Game arguments
        cmd.current_dir(instance_dir);
        cmd.arg("--gameDir").arg(instance_dir);
        let assets_dir = versions_dir.join("assets");
        fs::create_dir_all(&assets_dir)?;
        cmd.arg("--assetsDir").arg(assets_dir);
        cmd.arg("--version").arg(game_version);
        cmd.arg("--username").arg(username);
        cmd.arg("--uuid").arg(offline_uuid.to_string());
        cmd.arg("--accessToken").arg("0");
        // Jump straight into a save so scripted runs do not need a human on the title screen.
        if let Some(world) = quick_play_world {
            cmd.arg("--quickPlaySingleplayer").arg(world);
        }

        cmd.stdout(Stdio::from(log_file.try_clone()?));
        cmd.stderr(Stdio::from(log_file));

        info!("[Process] Spawning Minecraft client instance '{}' (loader: {}, headless: {})...", instance_name, loader, headless);
        let child = cmd.spawn().with_context(|| format!("Failed to spawn Minecraft process"))?;
        let pid = child.id();

        {
            let mut lk = self.current.lock().await;
            *lk = Some(ActiveProcess {
                instance_name: instance_name.to_string(),
                pid,
                session_token: session_token.to_string(),
                child,
            });
        }

        info!("[Process] Minecraft process spawned with PID {}", pid);
        Ok(pid)
    }

    pub async fn stop(&self) -> Result<()> {
        let mut lk = self.current.lock().await;
        if let Some(mut proc) = lk.take() {
            info!("[Process] Terminating active game process PID {}", proc.pid);
            let _ = proc.child.kill();
            let _ = proc.child.wait();
        }
        Ok(())
    }
}

fn collect_jars(dir: &Path, jars: &mut HashSet<String>) {
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                collect_jars(&path, jars);
            } else if path.extension().and_then(|s| s.to_str()) == Some("jar") {
                jars.insert(path.to_string_lossy().to_string());
            }
        }
    }
}

fn which_tool(name: &str) -> bool {
    let checker = if cfg!(windows) { "where" } else { "which" };
    Command::new(checker)
        .arg(name)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}
