use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub const EMBEDDED_FABRIC_1_21_1: &[u8] =
    include_bytes!("../assets/unai-bridge-fabric-1.21.1-1.0.0.jar");
pub const EMBEDDED_FORGE_1_21_1: &[u8] =
    include_bytes!("../assets/unai-bridge-forge-1.21.1-1.0.0.jar");

pub fn is_version_supported_1_20_plus(version: &str) -> bool {
    let parts: Vec<&str> = version.split('.').collect();
    if parts.is_empty() {
        return false;
    }
    if let Ok(major) = parts[0].parse::<u32>() {
        if major > 1 {
            return true;
        }
        if major == 1 && parts.len() >= 2 {
            if let Ok(minor) = parts[1].parse::<u32>() {
                return minor >= 20;
            }
        }
    }
    false
}

pub fn get_embedded_bridge(loader: &str, version: &str) -> Option<(&'static [u8], String)> {
    if !is_version_supported_1_20_plus(version) {
        return None;
    }
    let l = loader.to_lowercase();
    match l.as_str() {
        "fabric" | "quilt" => Some((
            EMBEDDED_FABRIC_1_21_1,
            format!("unai-bridge-fabric-{}-1.0.0.jar", version),
        )),
        "forge" | "neoforge" => Some((
            EMBEDDED_FORGE_1_21_1,
            format!("unai-bridge-forge-{}-1.0.0.jar", version),
        )),
        _ => None,
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstanceConfig {
    pub name: String,
    pub version: String,
    pub loader: String,
    pub created_at: u64,
    pub memory_mb: u32,
    pub jvm_args: Vec<String>,
    /// Save folder to jump straight into on launch, like "New World".
    /// Without it the client sits on the title screen and every scripted test needs a human
    /// to click Singleplayer, which defeats driving the client through the API.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub quick_play_world: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModInfo {
    pub filename: String,
    pub enabled: bool,
    pub size_bytes: u64,
}

pub struct InstanceManager {
    pub instances_dir: PathBuf,
}

impl InstanceManager {
    pub fn new(instances_dir: PathBuf) -> Self {
        Self { instances_dir }
    }

    pub fn ensure_dirs(&self) -> Result<()> {
        fs::create_dir_all(&self.instances_dir)
            .with_context(|| format!("Failed to create instances dir {:?}", self.instances_dir))?;
        Ok(())
    }

    pub fn instance_dir(&self, name: &str) -> PathBuf {
        self.instances_dir.join(name)
    }

    pub fn config_path(&self, name: &str) -> PathBuf {
        self.instance_dir(name).join("instance.json")
    }

    pub fn mods_dir(&self, name: &str) -> PathBuf {
        self.instance_dir(name).join("mods")
    }

    pub fn list(&self) -> Result<Vec<InstanceConfig>> {
        self.ensure_dirs()?;
        let mut list = Vec::new();
        for entry in fs::read_dir(&self.instances_dir)? {
            let entry = entry?;
            if entry.file_type()?.is_dir() {
                let name = entry.file_name().to_string_lossy().to_string();
                if let Ok(cfg) = self.get(&name) {
                    list.push(cfg);
                }
            }
        }
        list.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(list)
    }

    pub fn get(&self, name: &str) -> Result<InstanceConfig> {
        let path = self.config_path(name);
        if !path.exists() {
            return Err(anyhow!("Instance '{}' does not exist", name));
        }
        let content = fs::read_to_string(&path)?;
        let cfg: InstanceConfig = serde_json::from_str(&content)?;
        Ok(cfg)
    }

    pub fn create(
        &self,
        name: &str,
        version: &str,
        loader: &str,
        memory_mb: Option<u32>,
    ) -> Result<InstanceConfig> {
        let dir = self.instance_dir(name);
        if dir.exists() {
            return Err(anyhow!("Instance '{}' already exists at {:?}", name, dir));
        }

        if !is_version_supported_1_20_plus(version) {
            return Err(anyhow!(
                "Minecraft version '{}' is not supported. UnAI supports modern Minecraft versions >= 1.20 (1.20.x, 1.21.x+). Older versions (< 1.20) are deprecated and not supported.",
                version
            ));
        }

        let ldr = loader.to_lowercase();
        let (bridge_bytes, bridge_filename) = get_embedded_bridge(&ldr, version).ok_or_else(|| {
            anyhow!(
                "Unsupported loader '{}'. Supported loaders: fabric, quilt, forge, neoforge",
                loader
            )
        })?;

        fs::create_dir_all(&dir)?;
        fs::create_dir_all(dir.join("mods"))?;
        fs::create_dir_all(dir.join("config"))?;

        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        let cfg = InstanceConfig {
            name: name.to_string(),
            version: version.to_string(),
            loader: ldr,
            created_at: now,
            memory_mb: memory_mb.unwrap_or(2048),
            jvm_args: Vec::new(),
            quick_play_world: None,
        };

        let content = serde_json::to_string_pretty(&cfg)?;
        fs::write(self.config_path(name), content)?;

        // Auto-install matching UnAI Bridge Mod into mods/
        let bridge_path = dir.join("mods").join(bridge_filename);
        fs::write(bridge_path, bridge_bytes)?;

        // Seed basic options.txt if not exists
        let options_path = dir.join("options.txt");
        if !options_path.exists() {
            let initial_options =
                "renderDistance:4\nmaxFps:60\nenableVsync:false\ngraphicsMode:fast\n";
            let _ = fs::write(&options_path, initial_options);
        }

        Ok(cfg)
    }

    pub fn delete(&self, name: &str) -> Result<()> {
        let dir = self.instance_dir(name);
        if !dir.exists() {
            return Err(anyhow!("Instance '{}' does not exist", name));
        }
        fs::remove_dir_all(&dir)
            .with_context(|| format!("Failed to remove instance directory {:?}", dir))?;
        Ok(())
    }

    pub fn install_mod_file(&self, name: &str, source_path: &Path) -> Result<PathBuf> {
        if !source_path.exists() {
            return Err(anyhow!("Source mod file {:?} not found", source_path));
        }
        let file_name = source_path
            .file_name()
            .ok_or_else(|| anyhow!("Invalid mod filename {:?}", source_path))?;
        let mods_dir = self.mods_dir(name);
        fs::create_dir_all(&mods_dir)?;
        let target = mods_dir.join(file_name);
        fs::copy(source_path, &target)
            .with_context(|| format!("Failed to copy mod to {:?}", target))?;
        Ok(target)
    }

    #[allow(dead_code)]
    pub fn list_mods(&self, name: &str) -> Result<Vec<String>> {
        let mods_dir = self.mods_dir(name);
        if !mods_dir.exists() {
            return Ok(Vec::new());
        }
        let mut mods = Vec::new();
        for entry in fs::read_dir(mods_dir)? {
            let entry = entry?;
            let fname = entry.file_name().to_string_lossy().to_string();
            if fname.ends_with(".jar") {
                mods.push(fname);
            }
        }
        mods.sort();
        Ok(mods)
    }

    pub fn remove_mod(&self, name: &str, filename: &str) -> Result<()> {
        let mods_dir = self.mods_dir(name);
        let target = mods_dir.join(filename);
        if target.exists() {
            fs::remove_file(&target)?;
            return Ok(());
        }
        let disabled = mods_dir.join(format!("{}.disabled", filename));
        if disabled.exists() {
            fs::remove_file(&disabled)?;
            return Ok(());
        }
        Err(anyhow!(
            "Mod file '{}' not found in instance '{}'",
            filename,
            name
        ))
    }

    pub fn toggle_mod(&self, name: &str, filename: &str, enable: bool) -> Result<String> {
        let mods_dir = self.mods_dir(name);
        if enable {
            let disabled_name = if filename.ends_with(".disabled") {
                filename.to_string()
            } else {
                format!("{}.disabled", filename)
            };
            let src = mods_dir.join(&disabled_name);
            if !src.exists() {
                return Err(anyhow!("Disabled mod '{}' not found", disabled_name));
            }
            let target_name = disabled_name.trim_end_matches(".disabled");
            let dst = mods_dir.join(target_name);
            fs::rename(&src, &dst)?;
            Ok(target_name.to_string())
        } else {
            let clean_name = filename.trim_end_matches(".disabled");
            let src = mods_dir.join(clean_name);
            if !src.exists() {
                return Err(anyhow!("Mod '{}' not found to disable", clean_name));
            }
            let dst_name = format!("{}.disabled", clean_name);
            let dst = mods_dir.join(&dst_name);
            fs::rename(&src, &dst)?;
            Ok(dst_name)
        }
    }

    pub fn list_mods_detailed(&self, name: &str) -> Result<Vec<ModInfo>> {
        let mods_dir = self.mods_dir(name);
        if !mods_dir.exists() {
            return Ok(Vec::new());
        }
        let mut list = Vec::new();
        for entry in fs::read_dir(mods_dir)? {
            let entry = entry?;
            let fname = entry.file_name().to_string_lossy().to_string();
            let meta = entry.metadata()?;
            if fname.ends_with(".jar") {
                list.push(ModInfo {
                    filename: fname,
                    enabled: true,
                    size_bytes: meta.len(),
                });
            } else if fname.ends_with(".jar.disabled") {
                list.push(ModInfo {
                    filename: fname,
                    enabled: false,
                    size_bytes: meta.len(),
                });
            }
        }
        list.sort_by(|a, b| a.filename.cmp(&b.filename));
        Ok(list)
    }

    pub fn configure_options(&self, name: &str, settings: &HashMap<String, String>) -> Result<()> {
        let options_path = self.instance_dir(name).join("options.txt");
        let mut lines = Vec::new();
        let mut existing_keys = HashMap::new();

        if options_path.exists() {
            let content = fs::read_to_string(&options_path)?;
            for line in content.lines() {
                if let Some((k, _)) = line.split_once(':') {
                    existing_keys.insert(k.trim().to_string(), lines.len());
                }
                lines.push(line.to_string());
            }
        }

        for (k, v) in settings {
            let line_val = format!("{}:{}", k, v);
            if let Some(&idx) = existing_keys.get(k) {
                lines[idx] = line_val;
            } else {
                lines.push(line_val);
            }
        }

        let out = lines.join("\n") + "\n";
        fs::write(&options_path, out)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_version_support_matrix_1_20_plus() {
        // Supported versions (>= 1.20)
        assert!(is_version_supported_1_20_plus("1.20"));
        assert!(is_version_supported_1_20_plus("1.20.1"));
        assert!(is_version_supported_1_20_plus("1.20.4"));
        assert!(is_version_supported_1_20_plus("1.20.6"));
        assert!(is_version_supported_1_20_plus("1.21"));
        assert!(is_version_supported_1_20_plus("1.21.1"));
        assert!(is_version_supported_1_20_plus("1.21.4"));
        assert!(is_version_supported_1_20_plus("1.22"));

        // Deprecated/unsupported versions (< 1.20)
        assert!(!is_version_supported_1_20_plus("1.19.4"));
        assert!(!is_version_supported_1_20_plus("1.19"));
        assert!(!is_version_supported_1_20_plus("1.18.2"));
        assert!(!is_version_supported_1_20_plus("1.16.5"));
        assert!(!is_version_supported_1_20_plus("1.12.2"));
        assert!(!is_version_supported_1_20_plus("1.8.9"));
        assert!(!is_version_supported_1_20_plus("invalid"));
        assert!(!is_version_supported_1_20_plus(""));
    }

    #[test]
    fn test_get_embedded_bridge() {
        // Fabric 1.21.1
        let fab = get_embedded_bridge("fabric", "1.21.1");
        assert!(fab.is_some());
        let (bytes, name) = fab.unwrap();
        assert_eq!(name, "unai-bridge-fabric-1.21.1-1.0.0.jar");
        assert!(!bytes.is_empty());

        // Forge 1.21.1
        let forge = get_embedded_bridge("forge", "1.21.1");
        assert!(forge.is_some());
        let (bytes_forge, name_forge) = forge.unwrap();
        assert_eq!(name_forge, "unai-bridge-forge-1.21.1-1.0.0.jar");
        assert!(!bytes_forge.is_empty());

        // NeoForge maps to Forge bridge
        let neoforge = get_embedded_bridge("neoforge", "1.20.4");
        assert!(neoforge.is_some());

        // Quilt maps to Fabric bridge
        let quilt = get_embedded_bridge("quilt", "1.20.1");
        assert!(quilt.is_some());

        // Versions below 1.20 are rejected
        assert!(get_embedded_bridge("fabric", "1.19.4").is_none());
        assert!(get_embedded_bridge("fabric", "1.16.5").is_none());
        assert!(get_embedded_bridge("forge", "1.12.2").is_none());

        // Unsupported loaders rejected
        assert!(get_embedded_bridge("vanilla", "1.21.1").is_none());
        assert!(get_embedded_bridge("liteloader", "1.21.1").is_none());
    }

    #[test]
    fn test_create_instance_rejects_old_versions() {
        let tmp_dir = std::env::temp_dir().join(format!("unai_test_{}", uuid::Uuid::new_v4()));
        let mgr = InstanceManager::new(tmp_dir.clone());

        // Attempting to create an instance with < 1.20 must fail
        let res_119 = mgr.create("test_119", "1.19.4", "fabric", None);
        assert!(res_119.is_err());
        assert!(res_119.unwrap_err().to_string().contains(">= 1.20"));

        let res_116 = mgr.create("test_116", "1.16.5", "forge", None);
        assert!(res_116.is_err());

        // Creating with >= 1.20 succeeds
        let res_121 = mgr.create("test_121", "1.21.1", "fabric", Some(4096));
        assert!(res_121.is_ok());
        let cfg = res_121.unwrap();
        assert_eq!(cfg.version, "1.21.1");
        assert_eq!(cfg.memory_mb, 4096);

        // Verify bridge jar was placed in mods/
        let mod_jar = tmp_dir.join("test_121").join("mods").join("unai-bridge-fabric-1.21.1-1.0.0.jar");
        assert!(mod_jar.exists());

        // Clean up
        let _ = fs::remove_dir_all(&tmp_dir);
    }
}
