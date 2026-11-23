pub(crate) fn validate_workers(workers: Option<u32>) -> Result<Option<u32>, &'static str> {
    match workers {
        None | Some(0) => Ok(None),
        Some(w) if (3..=100).contains(&w) => Ok(Some(w)),
        _ => Err("workers must be 0 (default) or between 3 and 100"),
    }
}

pub(crate) fn validate_text_field(
    value: &str,
    max_bytes: usize,
    message: &'static str,
) -> Result<(), &'static str> {
    if value.len() > max_bytes || value.chars().any(char::is_control) {
        return Err(message);
    }
    Ok(())
}

pub(crate) const MAX_CLIENT_NAME_BYTES: usize = 128;

pub(crate) fn validate_client_name(name: &str) -> Result<(), &'static str> {
    validate_text_field(
        name,
        MAX_CLIENT_NAME_BYTES,
        "client name is too long or invalid",
    )
}

pub(crate) const MAX_PROXY_PROFILE_NAME_BYTES: usize = 64;

pub(crate) fn validate_proxy_profile_name(name: &str) -> Result<(), &'static str> {
    validate_text_field(
        name,
        MAX_PROXY_PROFILE_NAME_BYTES,
        "proxy profile name is too long or invalid",
    )
}
