from PIL import Image
from rembg import remove

def make_transparent(input_path, output_path, threshold=225):
    """
    Converts a white/near-white background to transparent.
    
    :param input_path: Path to the input PNG.
    :param output_path: Path to save the transparent PNG.
    :param threshold: Sensitivity (0-255). Higher means more "off-white" 
                      colors will be removed.
    """
    # Load image and ensure it's in RGBA mode
    img = Image.open(input_path).convert("RGBA")
    data = img.getdata()

    new_data = []
    for item in data:
        # item is a tuple: (R, G, B, A)
        # Check if R, G, and B are all above the threshold
        if item[0] > threshold and item[1] > threshold and item[2] > threshold:
            # Replace with transparent pixel (0 alpha)
            new_data.append((255, 255, 255, 0))
        else:
            new_data.append(item)

    img.putdata(new_data)
    img.save(output_path, "PNG")
    print(f"Success! Saved to {output_path}")

# Usage
# make_transparent("../tasker-frontend/src/assets/Pineapple.png", "../tasker-frontend/src/assets/Pineapple_trans.png")

input_img = Image.open('../tasker-frontend/src/assets/Pineapple.png')
output_img = remove(input_img)
output_img.save('../tasker-frontend/src/assets/Pineapple_trans.png')