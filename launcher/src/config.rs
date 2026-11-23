use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LauncherConfig {
    pub instances_dir: PathBuf,
    pub versions_dir: PathBuf,
    pub port: u16,
    pub default_version: String,
    pub default_loader: String,
    pub default_auth: String,
}

impl Default for LauncherConfig {
    fn default() -> Self {
        let base_dir = dirs::home_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join(".unai")
            .join("minecraft");

        Self {
            instances_dir: base_dir.join("instances"),
            versions_dir: base_dir.join("versions"),
            port: 25585,
            default_version: "1.21.1".to_string(),
            default_loader: "fabric".to_string(),
            default_auth: "offline".to_string(),
        }
    }
}

impl LauncherConfig {
    pub fn config_path() -> PathBuf {
        dirs::home_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join(".unai")
            .join("minecraft")
            .join("config.toml")
    }

    pub fn load(custom_path: Option<&Path>) -> Result<Self> {
        let path = match custom_path {
            Some(p) => p.to_path_buf(),
            None => Self::config_path(),
        };

        if !path.exists() {
            let cfg = Self::default();
            cfg.save(Some(&path))?;
            return Ok(cfg);
        }

        let content = std::fs::read_to_string(&path)
            .with_context(|| format!("Failed to read config from {:?}", path))?;
        let cfg: LauncherConfig = toml::from_str(&content)
            .with_context(|| format!("Failed to parse TOML config from {:?}", path))?;
        Ok(cfg)
    }

    pub fn save(&self, custom_path: Option<&Path>) -> Result<()> {
        let path = match custom_path {
            Some(p) => p.to_path_buf(),
            None => Self::config_path(),
        };

        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("Failed to create directories for {:?}", parent))?;
        }

        let content = toml::to_string_pretty(self).context("Failed to serialize config to TOML")?;
        std::fs::write(&path, content)
            .with_context(|| format!("Failed to write config to {:?}", path))?;
        Ok(())
    }
}
