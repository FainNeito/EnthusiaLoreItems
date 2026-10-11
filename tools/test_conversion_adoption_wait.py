"""The runtime conversion fixture must wait for physical adoption, not row preparation."""

import re
import sqlite3
import unittest
from pathlib import Path


class ConversionAdoptionWaitTest(unittest.TestCase):
    def test_active_but_prepared_item_does_not_release_fixture(self):
        workflow = Path(__file__).resolve().parents[1] / ".github/workflows/wp05-conversion-protection-acceptance.yml"
        match = re.search(r'row=c.execute\("([^"]+)"', workflow.read_text(encoding="utf8"))
        if match is None:
            raise AssertionError("Conversion fixture adoption query is missing")
        query = match.group(1)
        with sqlite3.connect(":memory:") as database:
            database.executescript("""
                create table lore_definitions(definition_id text, lookup_key text);
                create table lore_instances(instance_id text, definition_id text, lifecycle_state text);
                create table pending_mutations(instance_id text, mutation_type text, state text);
                insert into lore_definitions values ('definition', 'prot_craft');
                insert into lore_instances values ('instance', 'definition', 'ACTIVE');
                insert into pending_mutations values ('instance', 'ADOPT_HELD_ITEM', 'PREPARED');
            """)
            self.assertEqual(0, database.execute(query, ("prot_craft",)).fetchone()[0])
            database.execute("update pending_mutations set state='COMPLETED'")
            self.assertEqual(1, database.execute(query, ("prot_craft",)).fetchone()[0])


if __name__ == "__main__":
    unittest.main()
