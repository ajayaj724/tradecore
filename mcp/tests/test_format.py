from tradecore.format import money, rupees, to_paise


def test_rupees_groups_indian_style():
    assert rupees(150000) == "₹1,500.00"
    assert rupees(100000000) == "₹10,00,000.00"
    assert rupees(5) == "₹0.05"


def test_rupees_keeps_sign():
    assert rupees(-997000) == "-₹9,970.00"


def test_money_carries_paise_and_display():
    assert money(150000) == {"paise": 150000, "display": "₹1,500.00"}


def test_to_paise_rounds_rupees():
    assert to_paise(1500.00) == 150000
    assert to_paise(1500.50) == 150050
    assert to_paise(0.016) == 2  # rounds up
