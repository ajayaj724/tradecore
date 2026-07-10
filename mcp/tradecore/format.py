"""Money helpers. The OMS speaks paise (integer minor units); the LLM speaks rupees.

Every paise figure is surfaced as both the raw integer and a ``₹`` string so the model
never mis-narrates minor units as rupees.
"""


def rupees(paise: int) -> str:
    """Format paise as an Indian-grouped rupee string, e.g. 150000 -> '₹1,500.00'."""
    negative = paise < 0
    whole, frac = divmod(abs(paise), 100)
    grouped = _group_indian(whole)
    return f"{'-' if negative else ''}₹{grouped}.{frac:02d}"


def money(paise: int) -> dict[str, object]:
    """A paise figure as both the raw integer and its rupee rendering."""
    return {"paise": paise, "display": rupees(paise)}


def to_paise(rupees_value: float) -> int:
    """Convert a rupee amount from the model to integer paise at the tool edge."""
    return round(rupees_value * 100)


def _group_indian(n: int) -> str:
    s = str(n)
    if len(s) <= 3:
        return s
    head, tail = s[:-3], s[-3:]
    parts = []
    while len(head) > 2:
        parts.insert(0, head[-2:])
        head = head[:-2]
    if head:
        parts.insert(0, head)
    return ",".join(parts) + "," + tail
