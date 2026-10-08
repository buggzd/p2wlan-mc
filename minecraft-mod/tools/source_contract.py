"""Pure source-anchor contract shared by independent runtime adaptations."""

class ContractError(RuntimeError):
    pass


def replace_once(source: str, before: str, after: str, label: str) -> str:
    if source.count(before) != 1:
        raise ContractError(f"upstream_contract_changed:{label}")
    return source.replace(before, after, 1)
