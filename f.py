import os
import sys

def fix_build_failure():
    path = 'app/src/main/java/com/assistant/overlay/interceptor/OmnipotentGoalkeeperEngine.kt'
    
    if not os.path.exists(path):
        print(f"[FATAL] File not found: {path}. Ensure you run this script from the repository root.")
        sys.exit(1)
        
    with open(path, 'r', encoding='utf-8') as f:
        lines = f.readlines()
        
    fixed = False
    # Target the exact structural anomaly
    for i in range(len(lines) - 4):
        if 'return true' in lines[i] and \
           lines[i+1].strip() == '}' and lines[i+1].startswith('                ') and \
           lines[i+2].strip() == '}' and lines[i+2].startswith('            ') and \
           lines[i+3].strip() == '}' and lines[i+3].startswith('        ') and \
           'MITIGATING SCENARIO 2' in lines[i+5]:
           
            print(f"[INFO] Found extra brace at line {i+3}. Removing it to fix scope leakage.")
            del lines[i+2] # Eradicate the orphaned 12-space brace
            fixed = True
            break
            
    if not fixed:
        print("[WARN] Exact pattern not found. Attempting structural fallback to balance braces.")
        open_count = 0
        in_function = False
        for i, line in enumerate(lines):
            if 'fun processGoalkeeperDefensiveLayer' in line:
                in_function = True
            
            if in_function:
                open_count += line.count('{')
                open_count -= line.count('}')
                
                if open_count < 0:
                    print(f"[INFO] Fallback: Removed extra closing brace at line {i+1}")
                    lines[i] = ''
                    break
                    
    with open(path, 'w', encoding='utf-8') as f:
        f.writelines(lines)
        
    # Verify structural integrity
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
        
    if content.count('{') == content.count('}'):
        print("[SUCCESS] Braces are now perfectly balanced. Scope leakage eradicated.")
        print("[SYSTEM] Run './gradlew assembleDebug' to verify compilation.")
    else:
        print(f"[FATAL] Braces are still unbalanced. Open: {content.count('{')}, Close: {content.count('}')}")
        sys.exit(1)

if __name__ == "__main__":
    fix_build_failure()
