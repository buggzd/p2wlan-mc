fn main() {
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
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
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
