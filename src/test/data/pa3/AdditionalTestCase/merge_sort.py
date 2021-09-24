def mergeSort(arr:[int]):
    mid:int = 0
    L:[int] = None
    R:[int] = None
    i:int = 0
    j:int = 0
    k:int = 0
    if len(arr) > 1:

         # Finding the mid of the array
        mid = len(arr)//2

        # Dividing the array elements
        L=[]
        while (i<mid):
            L = L + [arr[i]]
            i = i + 1

        # into 2 halves
        R = []
        while (i<len(arr)):
            R = R + [arr[i]]
            i = i + 1

        # Sorting the first half
        mergeSort(L)

        # Sorting the second half
        mergeSort(R)

        i = 0
        j = 0
        k = 0

        # Copy data to temp arrays L[] and R[]
        while(i < len(L) and j < len(R)):
            if L[i] < R[j]:
                arr[k] = L[i]
                i = i+ 1
            else:
                arr[k] = R[j]
                j = j+ 1
            k = k+ 1

        # Checking if any element was left
        while i < len(L):
            arr[k] = L[i]
            i = i+ 1
            k = k+ 1

        while j < len(R):
            arr[k] = R[j]
            j =j + 1
            k =k + 1

# Code to print the list


def printList(arr:[int]):
    x:int = 0
    for x in arr:
        print(x)


# Driver Code
arr:[int] = None
arr = [12, 11, 13, 5, 6, 7]
print("Given array is")
printList(arr)
mergeSort(arr)
print("Sorted array is: ")
printList(arr)

# This code is contributed by Mayank Khanna