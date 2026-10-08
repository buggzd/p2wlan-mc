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
    let result = runtime.block_on(p2wlan_mc_agent::run(
        tokio::io::stdin(),
        tokio::io::stdout(),
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
