def min(x:int,y:int) -> int:
    if x>y:
        return y
    else:
        return x
# Function to reverse every sub-array
# formed by consecutive k elements
def reverse(arr:[int], n:int, k:int):
    i:int = 0
    left:int = 0
    right:int = 0
    temp:int = 0
    while(i<n):

        left = i

        # To handle case when k is not
        # multiple of n
        right = min(i + k - 1, n - 1)

        # Reverse the sub-array [left, right]
        while (left < right):
            temp = arr[left]
            arr[left] = arr[right]
            arr[right] = temp
            left = left + 1
            right = right - 1
        i = i+ k

# Driver code
arr:[int] = None
k:int = 3
n:int = 0
i:int = 0
arr = [1, 2, 3, 4, 5, 6,7, 8]
k = 3
n = len(arr)
reverse(arr, n, k)

for i in arr:
    print(i)