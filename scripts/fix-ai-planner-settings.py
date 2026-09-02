#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/io/github/sebrolens/vitalchronicle/android/MainActivity.kt")
text = path.read_text(encoding="utf-8")
old = '''            ListItem(headlineContent={Text("Advanced settings")},supportingContent={Text("Hardware, analysis window and explicit engine override")},trailingContent={Icon(if(vm.advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)},modifier=Modifier.clickable{vm.advancedOpen=!vm.advancedOpen})
'''
new = '''            ListItem(headlineContent={Text("Advanced settings")},supportingContent={Text("Hardware and explicit engine override · analysis scope is selected by the AI planner")},trailingContent={Icon(if(vm.advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)},modifier=Modifier.clickable{vm.advancedOpen=!vm.advancedOpen})
'''
if old not in text:
    raise SystemExit("Advanced settings text block not found")
text = text.replace(old, new, 1)
old_selector = '''                Text("Analysis interval",fontWeight=FontWeight.Medium); Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(7,28,90).forEach{d->FilterChip(selected=vm.analysisDays==d,onClick={vm.analysisDays=d},label={Text("${d}d")})}}
'''
new_selector = '''                Text("Analysis scope is chosen automatically from the question and available local data.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
'''
if old_selector not in text:
    raise SystemExit("Legacy analysis interval selector not found")
text = text.replace(old_selector, new_selector, 1)
path.write_text(text, encoding="utf-8")
print("Removed legacy fixed analysis interval selector")
