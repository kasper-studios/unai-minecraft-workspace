use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use tracing::info;

pub const MODRINTH_API_URL: &str = "https://api.modrinth.com/v2";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModrinthSearchResponse {
    pub hits: Vec<ModrinthHit>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModrinthHit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub categories: Vec<String>,
    pub versions: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModrinthVersion {
    pub id: String,
    pub project_id: String,
    pub name: String,
    pub version_number: String,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub files: Vec<ModrinthFile>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModrinthFile {
    pub url: String,
    pub filename: String,
    pub primary: bool,
    pub size: u64,
}

pub struct ModrinthClient {
    client: reqwest::Client,
}

impl ModrinthClient {
    pub fn new() -> Self {
        let client = reqwest::Client::builder()
            .user_agent("KasperStudios/UnAI-Launcher/2.0.0 (admin@kasperstudios.xyz)")
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .unwrap_or_default();
        Self { client }
    }

    pub async fn resolve_and_download(
        &self,
        query: &str,
        loader: &str,
        game_version: &str,
        target_dir: &Path,
    ) -> Result<PathBuf> {
        fs::create_dir_all(target_dir)?;

        // 1. If it's a URL, download directly
        if query.starts_with("http://") || query.starts_with("https://") {
            let url = query;
            let filename = url
                .split('/')
                .last()
                .and_then(|s| s.split('?').next())
                .filter(|s| !s.is_empty())
                .unwrap_or("downloaded_mod.jar");
            let target_path = target_dir.join(filename);
            self.download_file_to(url, &target_path).await?;
            return Ok(target_path);
        }

        // 2. Try fetching versions directly by slug/project_id
        let clean_slug = query.trim().to_lowercase();
        if let Ok(Some(ver)) = self.fetch_project_version(&clean_slug, loader, game_version).await {
            return self.download_version_file(&ver, target_dir).await;
        }

        // 3. Search Modrinth by query
        info!("[Modrinth] Searching query '{}' for {} {}", query, loader, game_version);
        let hits = self.search(query, loader, game_version).await?;
        if hits.is_empty() {
            return Err(anyhow!("No mods found on Modrinth for '{}' ({}/{})", query, loader, game_version));
        }

        let first = &hits[0];
        info!("[Modrinth] Found match: {} ({})", first.title, first.slug);

        if let Some(ver) = self.fetch_project_version(&first.slug, loader, game_version).await? {
            return self.download_version_file(&ver, target_dir).await;
        }

        Err(anyhow!(
            "Mod '{}' found, but no compatible version exists for {} on {}",
            first.title, loader, game_version
        ))
    }

    pub async fn search(&self, query: &str, loader: &str, game_version: &str) -> Result<Vec<ModrinthHit>> {
        let facets = format!(
            "[[\"categories:{}\"],[\"versions:{}\"],[\"project_type:mod\"]]",
            loader.to_lowercase(),
            game_version
        );
        let url = format!("{}/search?query={}&facets={}&limit=5", MODRINTH_API_URL, query, facets);

        let resp = self.client.get(&url).send().await?.json::<ModrinthSearchResponse>().await?;
        Ok(resp.hits)
    }

    pub async fn fetch_project_version(
        &self,
        slug_or_id: &str,
        loader: &str,
        game_version: &str,
    ) -> Result<Option<ModrinthVersion>> {
        let url = format!(
            "{}/project/{}/version?loaders=[\"{}\"]&game_versions=[\"{}\"]",
            MODRINTH_API_URL,
            slug_or_id,
            loader.to_lowercase(),
            game_version
        );

        let resp = self.client.get(&url).send().await?;
        if !resp.status().is_success() {
            return Ok(None);
        }

        let versions = resp.json::<Vec<ModrinthVersion>>().await?;
        Ok(versions.into_iter().next())
    }

    async fn download_version_file(&self, version: &ModrinthVersion, target_dir: &Path) -> Result<PathBuf> {
        let file_info = version
            .files
            .iter()
            .find(|f| f.primary)
            .or_else(|| version.files.first())
            .ok_or_else(|| anyhow!("No files found in version {}", version.name))?;

        let target_path = target_dir.join(&file_info.filename);
        info!("[Modrinth] Downloading {} -> {:?}", file_info.filename, target_path);
        self.download_file_to(&file_info.url, &target_path).await?;
        Ok(target_path)
    }

    pub async fn download_file_to(&self, url: &str, target_path: &Path) -> Result<()> {
        let resp = self.client.get(url).send().await.with_context(|| format!("Failed to GET {}", url))?;
        if !resp.status().is_success() {
            return Err(anyhow!("HTTP error {} downloading {}", resp.status(), url));
        }
        let bytes = resp.bytes().await?;
        fs::write(target_path, bytes)?;
        Ok(())
    }
}
