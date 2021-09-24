
# Function to do insertion sort
def insertionSort(arr:[int]):
    i:int = 1
    j:int = 0
    key:int = 0
    # Traverse through 1 to len(arr)
    while (i<len(arr)):

        key = arr[i]

        # Move elements of arr[0..i-1], that are
        # greater than key, to one position ahead
        # of their current position
        j = i-1
        while j >= 0 and key < arr[j] :
            arr[j + 1] = arr[j]
            j = j - 1
        arr[j + 1] = key
        i = i + 1


# Driver code to test above
arr:[int] = None
x:int = 0
arr = [12, 11, 13, 5, 6]
insertionSort(arr)
for x in arr:
    print(x)

