with open("./app/src/main/java/com/assistant/gameplay_engine.kt", "r") as f:
    lines = f.readlines()

out = []
skip = 0
for i, line in enumerate(lines):
    if skip > 0:
        skip -= 1
        continue
    if "val boosterAlive =" in line and i+1 < len(lines) and "try {" in lines[i+1]:
        out.append("        val boosterAlive = try { com.assistant.BoosterIgnition.isFleetReady() } catch (_: Throwable) { false }\n")
        skip = 7
    else:
        out.append(line)

with open("./app/src/main/java/com/assistant/gameplay_engine.kt", "w") as f:
    f.writelines(out)
