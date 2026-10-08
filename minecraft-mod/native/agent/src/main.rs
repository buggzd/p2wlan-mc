fn build_info() -> serde_json::Value {
    serde_json::json!({
        "schema": 1,
        "protocol": p2wlan_mc_protocol::VERSION,
        "version": env!("CARGO_PKG_VERSION"),
        "source_revision": option_env!("P2WLAN_MC_SOURCE_REVISION").unwrap_or("unattested"),
        "source_sha256": option_env!("P2WLAN_MC_SOURCE_SHA256").unwrap_or("unattested"),
        "build_kind": option_env!("P2WLAN_MC_BUILD_KIND").unwrap_or("unattested"),
        "os": std::env::consts::OS,
        "arch": std::env::consts::ARCH,
    })
}

fn main() {
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
    if arguments.as_slice() == ["--build-info"] {
        use std::io::Write;
        let mut output = std::io::stdout().lock();
        if serde_json::to_writer(&mut output, &build_info()).is_err()
            || output.write_all(b"\n").is_err()
        {
            std::process::exit(2);
        }
        return;
    }
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .max_blocking_threads(8)
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(_) => {
            eprintln!("AGENT_RUNTIME_UNAVAILABLE");
            std::process::exit(2);
        }
    };
    let profile = match arguments.as_slice() {
        [] => None,
        [flag, path] if flag == "--profile-dir" => Some(std::path::PathBuf::from(path)),
        _ => {
            eprintln!("INVALID_ARGUMENTS");
            std::process::exit(2);
        }
    };
    let result = runtime.block_on(p2wlan_mc_agent::run_with_profile(
        tokio::io::stdin(),
        tokio::io::stdout(),
        profile.as_deref(),
    ));
    runtime.shutdown_timeout(std::time::Duration::from_secs(2));
    match result {
        Ok(()) => std::process::exit(0),
        Err(error) => {
            eprintln!("{}", error.code());
            std::process::exit(2);
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn public_build_info_has_only_fixed_schema_fields() {
        let info = super::build_info();
        assert_eq!(info.as_object().map(|object| object.len()), Some(8));
        assert_eq!(info["schema"], 1);
        assert_eq!(info["protocol"], p2wlan_mc_protocol::VERSION);
        assert_eq!(info["version"], env!("CARGO_PKG_VERSION"));
        assert_eq!(info["os"], std::env::consts::OS);
        assert_eq!(info["arch"], std::env::consts::ARCH);
    }
}
