use crate::instance::{get_embedded_bridge, EMBEDDED_FABRIC_1_21_1};
use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use tracing::{info, warn};

pub const MOJANG_MANIFEST_URL: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
pub const AUTHLIB_INJECTOR_URL: &str =
    "https://authlib-injector.yushi.moe/artifact/latest.jar";
pub const FABRIC_META_URL: &str = "https://meta.fabricmc.net/v2/versions/loader";
pub const FORGE_PROMOTIONS_URL: &str =
    "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
#[allow(dead_code)]
pub const GITHUB_REPO_RELEASES: &str =
    "https://github.com/kasper-studios/unai-minecraft-workspace/releases";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MojangVersionEntry {
    pub id: String,
    pub r#type: String,
    pub url: String,
    pub time: String,
    #[serde(rename = "releaseTime")]
    pub release_time: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MojangManifest {
    pub latest: serde_json::Value,
    pub versions: Vec<MojangVersionEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FabricLoaderEntry {
    pub loader: FabricLoaderInfo,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FabricLoaderInfo {
    pub version: String,
    pub stable: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ForgePromotions {
    pub promos: HashMap<String, String>,
}

pub struct Downloader {
    client: reqwest::Client,
    pub versions_dir: PathBuf,
}

impl Downloader {
    pub fn new(versions_dir: PathBuf) -> Self {
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(60))
            .build()
            .unwrap_or_default();
        Self {
            client,
            versions_dir,
        }
    }

    pub fn ensure_dirs(&self) -> Result<()> {
        fs::create_dir_all(&self.versions_dir)
            .with_context(|| format!("Failed to create {:?}", self.versions_dir))?;
        Ok(())
    }

    pub async fn download_file(&self, url: &str, target_path: &Path) -> Result<()> {
        if let Some(parent) = target_path.parent() {
            fs::create_dir_all(parent)?;
        }
        info!("[Downloader] GET {} -> {:?}", url, target_path);
        let resp = self
            .client
            .get(url)
            .send()
            .await
            .with_context(|| format!("Failed to fetch {}", url))?;
        if !resp.status().is_success() {
            return Err(anyhow!("HTTP error {} fetching {}", resp.status(), url));
        }
        let bytes = resp.bytes().await?;
        fs::write(target_path, bytes)?;
        Ok(())
    }

    pub async fn fetch_mojang_manifest(&self) -> Result<MojangManifest> {
        info!("[Downloader] Fetching Mojang version manifest...");
        let resp = self
            .client
            .get(MOJANG_MANIFEST_URL)
            .send()
            .await?
            .json::<MojangManifest>()
            .await?;
        Ok(resp)
    }

    pub async fn ensure_authlib_injector(&self) -> Result<PathBuf> {
        self.ensure_dirs()?;
        let target = self.versions_dir.join("authlib-injector.jar");
        if !target.exists() {
            info!("[Downloader] Downloading authlib-injector for Ely.by skin support...");
            if let Err(e) = self.download_file(AUTHLIB_INJECTOR_URL, &target).await {
                warn!("[Downloader] Failed to download authlib-injector ({}), continuing without skin proxy...", e);
            }
        }
        Ok(target)
    }

    pub async fn get_latest_fabric_loader(&self, game_version: &str) -> Result<String> {
        let url = format!("{}/{}", FABRIC_META_URL, game_version);
        info!("[Downloader] Querying Fabric meta for {}: {}", game_version, url);
        let resp = self
            .client
            .get(&url)
            .send()
            .await?
            .json::<Vec<FabricLoaderEntry>>()
            .await?;

        if let Some(first) = resp.first() {
            Ok(first.loader.version.clone())
        } else {
            Err(anyhow!("No Fabric loader found for game version {}", game_version))
        }
    }

    pub async fn get_forge_version(&self, game_version: &str) -> Result<String> {
        let resp = self
            .client
            .get(FORGE_PROMOTIONS_URL)
            .send()
            .await?
            .json::<ForgePromotions>()
            .await?;

        let key_rec = format!("{}-recommended", game_version);
        let key_lat = format!("{}-latest", game_version);

        if let Some(ver) = resp.promos.get(&key_rec).or_else(|| resp.promos.get(&key_lat)) {
            Ok(ver.clone())
        } else {
            Err(anyhow!("No Forge build found for Minecraft {}", game_version))
        }
    }

    pub async fn get_neoforge_version(&self, game_version: &str) -> Result<String> {
        let parts: Vec<&str> = game_version.split('.').collect();
        let prefix = if parts.len() >= 2 && parts[0] == "1" {
            let minor = parts[1];
            let patch = parts.get(2).unwrap_or(&"0");
            format!("{}.{}.", minor, patch)
        } else {
            format!("{}.", game_version)
        };

        info!("[Downloader] Looking up NeoForge version for {} (prefix {})", game_version, prefix);
        let resp = self
            .client
            .get("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge")
            .send()
            .await;

        if let Ok(r) = resp {
            if let Ok(data) = r.json::<serde_json::Value>().await {
                if let Some(versions) = data.get("versions").and_then(|v| v.as_array()) {
                    let matching: Vec<&str> = versions
                        .iter()
                        .filter_map(|v| v.as_str())
                        .filter(|v| v.starts_with(&prefix))
                        .collect();

                    if let Some(latest) = matching.last() {
                        return Ok(latest.to_string());
                    }
                }
            }
        }

        // Fallback for 1.21.1 if maven api is unreachable
        if game_version == "1.21.1" {
            return Ok("21.1.227".to_string());
        }

        Err(anyhow!("No NeoForge release found for Minecraft {}", game_version))
    }

    #[allow(dead_code)]
    pub async fn ensure_bridge_mod(&self, loader: &str, version: &str, target_dir: &Path) -> Result<PathBuf> {
        if !crate::instance::is_version_supported_1_20_plus(version) {
            return Err(anyhow!(
                "Minecraft version '{}' is not supported (minimum version is 1.20)",
                version
            ));
        }
        fs::create_dir_all(target_dir)?;

        // 1. Check embedded binaries
        if let Some((bytes, filename)) = get_embedded_bridge(loader, version) {
            let target = target_dir.join(&filename);
            if !target.exists() {
                fs::write(&target, bytes)?;
                info!("[Bridge Resolver] Wrote embedded bridge '{}'", filename);
            }
            return Ok(target);
        }

        // 2. Check local bridges cache in versions_dir/bridges/
        let bridges_cache = self.versions_dir.join("bridges");
        fs::create_dir_all(&bridges_cache)?;
        let expected_name = format!("unai-bridge-{}-{}-1.0.0.jar", loader.to_lowercase(), version);
        let cached = bridges_cache.join(&expected_name);
        if cached.exists() {
            let target = target_dir.join(&expected_name);
            fs::copy(&cached, &target)?;
            return Ok(target);
        }

        // 3. Download from GitHub Releases
        let release_url = format!("{}/latest/download/{}", GITHUB_REPO_RELEASES, expected_name);
        info!("[Bridge Resolver] Downloading bridge mod from GitHub Releases: {}", release_url);
        match self.download_file(&release_url, &cached).await {
            Ok(_) => {
                let target = target_dir.join(&expected_name);
                fs::copy(&cached, &target)?;
                Ok(target)
            }
            Err(e) => {
                warn!("[Bridge Resolver] Failed to fetch exact bridge {}: {}. Falling back to default embedded Fabric bridge.", expected_name, e);
                let fallback = target_dir.join("unai-bridge-fabric-1.21.1-1.0.0.jar");
                fs::write(&fallback, EMBEDDED_FABRIC_1_21_1)?;
                Ok(fallback)
            }
        }
    }

    pub async fn install_version(&self, game_version: &str, loader: &str) -> Result<PathBuf> {
        if !crate::instance::is_version_supported_1_20_plus(game_version) {
            return Err(anyhow!(
                "Minecraft version '{}' is not supported. UnAI supports modern Minecraft versions >= 1.20 (1.20.x, 1.21.x+). Older versions (< 1.20) are deprecated and not supported.",
                game_version
            ));
        }

        self.ensure_dirs()?;
        let ver_dir = self.versions_dir.join(game_version);
        fs::create_dir_all(&ver_dir)?;

        // 1. Download Mojang client JSON and JAR
        let manifest = self.fetch_mojang_manifest().await?;
        let entry = manifest
            .versions
            .iter()
            .find(|v| v.id == game_version)
            .ok_or_else(|| anyhow!("Minecraft version '{}' not found in Mojang manifest", game_version))?;

        let version_json_path = ver_dir.join(format!("{}.json", game_version));
        let client_jar_path = ver_dir.join(format!("{}.jar", game_version));

        if !version_json_path.exists() {
            info!("[Downloader] Downloading version metadata from {}", entry.url);
            self.download_file(&entry.url, &version_json_path).await?;
        }

        let ver_json_raw = fs::read_to_string(&version_json_path)?;
        let ver_json: serde_json::Value = serde_json::from_str(&ver_json_raw)?;

        if !client_jar_path.exists() {
            if let Some(client_url) = ver_json
                .pointer("/downloads/client/url")
                .and_then(|v| v.as_str())
            {
                info!("[Downloader] Downloading client JAR: {}", client_url);
                self.download_file(client_url, &client_jar_path).await?;
            } else {
                return Err(anyhow!("No client download URL in version metadata"));
            }
        }

        // 2. Download authlib-injector for skins
        let _ = self.ensure_authlib_injector().await?;

        // 3. Loader profiles & Libraries
        let global_libs_dir = self.versions_dir.join("libraries");
        fs::create_dir_all(&global_libs_dir)?;

        let ldr = loader.to_lowercase();
        match ldr.as_str() {
            "fabric" | "quilt" => {
                let loader_ver = self.get_latest_fabric_loader(game_version).await?;
                let fabric_profile_url = format!(
                    "https://meta.fabricmc.net/v2/versions/loader/{}/{}/profile/json",
                    game_version, loader_ver
                );
                let fabric_json_path = ver_dir.join(format!("{}-fabric-{}.json", game_version, loader_ver));
                if !fabric_json_path.exists() {
                    info!("[Downloader] Downloading Fabric profile: {}", fabric_profile_url);
                    self.download_file(&fabric_profile_url, &fabric_json_path).await?;
                }

                // Download all Fabric loader dependencies
                if let Ok(raw) = fs::read_to_string(&fabric_json_path) {
                    if let Ok(fab_json) = serde_json::from_str::<serde_json::Value>(&raw) {
                        if let Some(libs) = fab_json.get("libraries").and_then(|v| v.as_array()) {
                            for lib in libs {
                                if let (Some(name), Some(url_base)) = (
                                    lib.get("name").and_then(|v| v.as_str()),
                                    lib.get("url").and_then(|v| v.as_str()),
                                ) {
                                    let parts: Vec<&str> = name.split(':').collect();
                                    if parts.len() >= 3 {
                                        let group_path = parts[0].replace('.', "/");
                                        let artifact = parts[1];
                                        let ver = parts[2];
                                        let jar_name = format!("{}-{}.jar", artifact, ver);
                                        let rel_path = format!("{}/{}/{}/{}", group_path, artifact, ver, jar_name);
                                        let target_lib = global_libs_dir.join(&rel_path);

                                        if !target_lib.exists() {
                                            let base = if url_base.ends_with('/') {
                                                url_base.to_string()
                                            } else {
                                                format!("{}/", url_base)
                                            };
                                            let download_url = format!("{}{}", base, rel_path);
                                            let _ = self.download_file(&download_url, &target_lib).await;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "forge" => {
                let forge_ver = self.get_forge_version(game_version).await?;
                let installer_url = format!(
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/{}-{}/forge-{}-{}-installer.jar",
                    game_version, forge_ver, game_version, forge_ver
                );
                let installer_path = ver_dir.join(format!("forge-{}-{}-installer.jar", game_version, forge_ver));
                if !installer_path.exists() {
                    info!("[Downloader] Downloading Forge installer: {}", installer_url);
                    self.download_file(&installer_url, &installer_path).await?;
                }
            }
            "neoforge" => {
                let neoforge_ver = match self.get_neoforge_version(game_version).await {
                    Ok(v) => v,
                    Err(e) => {
                        warn!("[Downloader] NeoForge version resolution warning: {}. Falling back to 21.1.227", e);
                        "21.1.227".to_string()
                    }
                };
                let neoforge_url = format!(
                    "https://maven.neoforged.net/releases/net/neoforged/neoforge/{}/neoforge-{}-installer.jar",
                    neoforge_ver, neoforge_ver
                );
                let installer_path = ver_dir.join(format!("neoforge-{}-installer.jar", game_version));
                if !installer_path.exists() {
                    info!("[Downloader] Downloading NeoForge installer ({}): {}", neoforge_ver, neoforge_url);
                    let _ = self.download_file(&neoforge_url, &installer_path).await;
                }
            }
            _ => {}
        }

        // 4. Download Mojang libraries
        if let Some(libs) = ver_json.get("libraries").and_then(|v| v.as_array()) {
            for lib in libs {
                if let Some(art) = lib.pointer("/downloads/artifact") {
                    if let (Some(rel_path), Some(dl_url)) = (
                        art.get("path").and_then(|v| v.as_str()),
                        art.get("url").and_then(|v| v.as_str()),
                    ) {
                        let target_lib = global_libs_dir.join(rel_path);
                        if !target_lib.exists() {
                            let _ = self.download_file(dl_url, &target_lib).await;
                        }
                    }
                }
            }
        }

        info!("[Downloader] Version {} ({}) successfully prepared at {:?}", game_version, loader, ver_dir);
        Ok(ver_dir)
    }
}
